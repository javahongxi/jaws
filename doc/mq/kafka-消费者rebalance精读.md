# Kafka 消费者 Rebalance 机制精读

> 主题边界：本文讲 Kafka 消费组的再均衡（rebalance）——**三代协议的演进动因**（Eager → Cooperative/KIP-429 → KIP-848 服务端协议）、触发源、时间参数链、状态机与无屏障收敛，并在 §6 对照 RocketMQ 的客户端自主 rebalance。Kafka 侧结论按本地 trunk（4.5.0-SNAPSHOT，已含 4.0 GA 的 KIP-848）源码核实，行号随文标注；RocketMQ 侧只引已核实的类与行号，机制细节回链[消费链路精读 §1.3](rocketmq-消费链路精读.md)。分配算法的均衡度证明、位点提交语义（`commitSync`/`commitAsync` 交错）、share group / streams group 不在本文展开。

## 1. 触发：什么让这个组重新分队

消费组的本质是**分区到成员的独占映射**（同组内一个分区只能被一个成员消费）。任何让"成员集合"或"分区集合"发生变化的事件，都必须重算这张映射——这就是 rebalance。四类触发源：

| 触发源 | 具体事件 | 判定点 |
|---|---|---|
| 成员进出 | 新 consumer `subscribe()`、正常 `close()`（主动 LeaveGroup）、进程崩溃 | coordinator 按成员心跳租约判死 |
| 应用线程卡死 | 两次 `poll()` 间隔超过 `max.poll.interval.ms` | 客户端自己判定并发起退出 |
| 订阅/元数据变化 | 订阅集变化、topic 分区扩容、正则订阅匹配到新 topic | 客户端检测到 subscription/metadata 版本变化，或服务端重算 target assignment |
| 协调者迁移 | GroupCoordinator 所在 broker 下线（controller 重指派）| 新 coordinator 返回 `NOT_LEADER_OR_FOLLOWER`/空组，成员重新入组 |

**两个超时必须分开理解**（这是误配的最大来源）：`session.timeout.ms` 由**后台心跳线程**喂，兜"进程死了"；`max.poll.interval.ms` 由**应用线程**喂，兜"进程活着但卡在业务里"。KIP-62 之后心跳已独立成线程，所以死循环卡在 `poll()` 之外时走的是后者而不是前者。当前默认值：`session.timeout.ms=45000`、`heartbeat.interval.ms=3000`、`max.poll.interval.ms=300000`（ConsumerConfig 的 define 块）。另有一条不显眼但关键的换算：classic 协议下 `JoinGroup` 请求里的 `rebalance.timeout.ms` 字段，对 consumer 类型直接取 `max.poll.interval.ms`（GroupRebalanceConfig L53-57），而 `rebalance.timeout.ms` 这个配置只对 connect/streams 这类非 consumer 协议生效（同文件 L60）——所以 coordinator 等待全员到齐的预算 = 应用线程允许的最长停顿，不是另一个独立旋钮。

## 2. 第一代：Eager——先全部交出来，再等重新发

classic 协议的 `JoinGroup → SyncGroup` 两步里，**leader（第一个入组的成员）在客户端本地跑 assignor 算出全量分配表**，coordinator 只负责转交。Eager 的含义在退出侧：`ConsumerCoordinator.onJoinPrepare` 的 EAGER 分支（L828-836）无条件把**名下所有分区**交给 `invokePartitionsRevoked` 并 `assignFromSubscribed(emptySet)`——先清空，再入组，再等 SyncGroup 拿回（可能完全相同的）分配。

三个后果由此必然：

1. **stop-the-world**：全组每个成员在 rebalance 期间消费完全中断，中断时长 = 最慢那个成员的回调耗时 + 全员到齐的等待（coordinator 在 rebalance timeout 窗口内等够所有成员才出票）。一个成员慢，全组陪跑。
2. **重复处理放大**：交出分区前必须把位点提交掉，否则接手者从旧位点重放。Eager 为此还额外做了 fetch 冻结——`maybeMarkPartitionsPendingRevocation` 只在 `protocol == EAGER` 时执行（L868-871）：提交位点是异步的，在"提交发出"到"撤销完成"的窗口里，已在途的 fetch 响应会继续推进应用 position，导致提交值落后于实际读到的位置，于是直接停掉这些分区的取数与投递。这段注释本身就是 Eager 语义代价的自白。
3. **协议退化**：assignor 通过 `supportedProtocols()` 声明能力，默认只声明 EAGER（ConsumerPartitionAssignor L86-88），而组内取的是**所有成员支持列表的交集**（ConsumerCoordinator L156/L257）——只要有一个成员用了老 assignor 或自定义 assignor，整组退回 Eager。这就是"明明配了 CooperativeStickyAssignor 却还是全量抖动"的常见原因。
4. **非自愿退出走 `onPartitionsLost`**：若 `generation`/`memberId` 已被心跳线程重置（`NO_GENERATION`，L816-826），说明这个成员早就不在组里了，此时回调的是 `invokePartitionsLost` 而不是 `Revoked`——语义差别在于"没机会收尾"，业务应丢弃在途状态、不要提交位点。

## 3. 第二代：Cooperative（KIP-429）——增量撤销，但仍有全局屏障

改的是**退场方式**，不改**协商方式**。核心在 `onJoinComplete`（L425-452）：拿到新一轮 assignment 后不直接覆盖，而是先算 `revoked = owned - assigned`，只对**真正被拿走**的分区回调 `invokePartitionsRevoked`，并且一旦 revoke 过就立即 `requestRejoin` 再走一轮（L445-451）；`onJoinPrepare` 侧同样只撤销"订阅里已经不存在的 topic"（L838-853）。所以一次分区迁移至少两轮心跳：**第一轮只减法，第二轮只加法**，被保留的分区全程不断流。

代价与残留问题：

- **两轮是协议要求，不是优化选项**，并且协议反过来约束 assignor：leader 算完分配后要做一次 `validateCooperativeAssignment`（L726-751，调用点 L693-699）——把所有成员的 `added` 与 `revoked` 求并集后取交集，非空即说明某分区"还在原主手里就被直接转手"，抛 `IllegalStateException("Assignor supporting the COOPERATIVE protocol violates its requirements")`。合法写法只有一种：**本轮先从原主名下摘掉（谁都不给），下一轮再发给新主**（L721-724 javadoc 原文）。内置 `CooperativeStickyAssignor` 免检，因为它内部按 generation 自己跟踪 owned（L695-697）。
- 另外 `onJoinPrepare` 整体受 `rebalanceTimeoutMs` 约束（L756-759 建计时器）：位点异步提交没在预算内完成也要**继续入组**（L786-787 打 error 后放行）——宁可少提交一次，也不能因为收尾而超时被踢，这条边界解释了为什么 auto-commit 在 rebalance 里并不保证位点精确。
- **JoinGroup 依然要全员到齐**：屏障（barrier）没被拆掉，只是每次不再搬全部数据。组规模到千级、分区到万级时，"等最慢成员"仍然主导尾延迟。
- **算法仍在 leader 客户端**：一次 rebalance 需要 leader 拉齐全员 subscription、本地算完再 SyncGroup 下发，leader 成为单点与耗时源；`partition.assignment.strategy` 里塞自定义元数据（`subscriptionUserData`）会让组协议协商复杂度陡增。
- **迁移要显式配置**：`CooperativeStickyAssignor` 是唯一内置的 cooperative 实现，`RangeAssignor`/`StickyAssignor` 只支持 EAGER，混部期间按 §2 的交集规则退化。

一句话定位：**Cooperative 把"全组停顿"缩小成"迁移的分区停顿"，但保留了"全组同步"这个结构性瓶颈。**

## 4. 第三代：KIP-848——分配收归服务端，屏障整体拆除

### 4.1 机制：per-member 增量收敛，用 epoch 而非 barrier

新协议删掉了 `JoinGroup`/`SyncGroup`/`Heartbeat` 三支 RPC，成员只发 **`ConsumerGroupHeartbeat`**：请求里带上自己的订阅与**当前实际持有的分区**（`ownedTopicPartitions`，CurrentAssignmentBuilder L82-85），响应里带回服务端算好的目标分配。coordinator 侧对每个成员维护 `assignment`（已生效）与 `targetAssignment`（应得）两份账，配 **target assignment epoch** 逐成员推进（同文件 L54-60）：成员在下一次心跳确认收到新目标，确认到位即完成替换。**没有任何时刻要求全组同时静止**——这就是官方文档所说 "fully incremental design, which no longer relies on a global synchronization barrier"（docs/operations/consumer-rebalance-protocol.md L32）。

分配算法也搬到 broker 的 `group-coordinator` 模块：内置 `UniformAssignor`（默认）与 `RangeAssignor`（GroupCoordinatorConfig L221-232，`group.consumer.assignors` 可换/可扩），自定义要实现服务端 `ConsumerGroupPartitionAssignor` 接口——**客户端 assignor 不再是扩展点**。客户端侧只剩状态机：`ConsumerMembershipManager` 的 `UNSUBSCRIBED / JOINING / RECONCILING / ACKNOWLEDGING / STABLE / FENCED / PREPARE_LEAVING / LEAVING / FATAL / STALE`（MemberState.java L35-115），心跳由 `ConsumerHeartbeatRequestManager` 在后台线程驱动，应用线程走 `AsyncKafkaConsumer`，不再需要"poll 里偷偷跑协议"的耦合。

### 4.2 参数与语义变化

- **时间参数归服务端**：`group.consumer.session.timeout.ms` 默认 45000（min 45000 / max 60000）、`group.consumer.heartbeat.interval.ms` 默认 5000（min 5000 / max 15000）（GroupCoordinatorConfig L189-207）。客户端的 `session.timeout.ms`、`heartbeat.interval.ms`、`partition.assignment.strategy`、`enforceRebalance()` 在该协议下**直接不可用**（docs L72-78）——三个必须手工对齐的旋钮减为一个服务端策略，这是可扩展性的主要来源之一。
- **重算有节流与卸载**：`group.consumer.assignment.interval.ms` 默认 1000（min 0 / max 15000，GroupCoordinatorConfig L248-256）——成员进出不会逐条触发全量重算，而是按节拍合并；`group.consumer.assignor.offload.enable=true`（L260-262）把 assignor 计算甩到 coordinator 后台线程，不堵事件通道。大规模滚动发布时的"抖动风暴"由这两条压平。
- **组容量放开**：`group.consumer.max.size` 默认 `Integer.MAX_VALUE`，且该配置只作用于新协议（classic 仍用 `group.max.size`）。
- **正则订阅服务端解析**：新增 `subscribe(SubscriptionPattern, ...)`，正则改 RE2J 并在 broker 侧求值（docs L68），配 `group.consumer.regex.refresh.interval.ms`（默认 10min）刷新匹配集；客户端不再为正则轮询 metadata。
- **客户端 assignor 映射表**（docs L56-61）：`RangeAssignor→range`，`CooperativeStickyAssignor/StickyAssignor/RoundRobinAssignor→uniform`。迁移时照此对齐，否则均衡分布会变（range 的分段倾斜换成 uniform 的按分区轮转）。

### 4.3 时间线：为什么 4.0 GA 了默认还是 classic

`group.protocol` 的默认值至今仍是 `classic`（ConsumerConfig L121-123 明文），别被"4.0 已 GA"误导成"4.0 默认新协议"。准确口径来自 docs L31/L38/L66 与 KIP-1274（docs L95-100）：服务端自 4.0 起凭 `group.version` feature flag 自动具备新协议能力且 GA，但**消费者默认不启用**，必须显式 `group.protocol=consumer`；KIP-1274 给出的（文档措辞为 expected）**计划时间线**是 5.0 把客户端默认切到 `consumer`（仍保留 classic），6.0 客户端只支持 `consumer`（broker 继续兼容 classic 组）。默认不切的直接原因是**切换即协议迁移**：非空组的 classic→consumer 转换虽然支持在线（首个新协议成员入组即触发转换，docs L89），但前提是原 classic 组的 assignor **不嵌自定义元数据**；而自定义客户端 assignor 在新协议下没有对等实现（docs L104），rack-aware 也未完全补齐（docs L105）。迁移节奏由服务端 `group.consumer.migration.policy` 控制，默认 `BIDIRECTIONAL`，可选 `UPGRADE`/`DOWNGRADE`/`DISABLED`（GroupCoordinatorConfig L236-246 + ConsumerGroupMigrationPolicy L26-37）——`DISABLED` 下 classic 成员不允许加入非空的新协议组，是防止回滚踩坑的闸门。离线迁移则更简单：组空时自动双向转换（docs L85）。

## 5. 三代横向对比

| 维度 | Eager | Cooperative (KIP-429) | KIP-848 (consumer 协议) |
|---|---|---|---|
| 协商 RPC | JoinGroup + SyncGroup + Heartbeat | 同左 | ConsumerGroupHeartbeat 一支 |
| 谁算分配 | leader 成员，客户端 assignor | leader 成员，客户端 assignor | coordinator，服务端 assignor |
| 是否要全员到齐 | 是 | 是 | 否（无全局屏障） |
| 未参与迁移的分区 | 中断 | 不中断 | 不中断 |
| 一轮迁移的往返 | 1 轮（但全量搬） | ≥2 轮 | 每成员各自收敛，与组大小无关 |
| 会话/心跳参数 | 客户端三旋钮 | 客户端三旋钮 | 服务端下发 |
| 自定义策略落点 | `ConsumerPartitionAssignor` | 同左 | `ConsumerGroupPartitionAssignor`（broker 侧） |
| 客户端默认路径 | **是**（默认 `RangeAssignor` 只声明 EAGER） | 需显式选 `CooperativeStickyAssignor` | 需 `group.protocol=consumer`（计划 5.0 起默认） |
| 主要瓶颈 | 组大小 × 停顿 | 屏障等待 + leader 单点 | 只剩单成员回调耗时 |

**调参清单**（按收益排序）：① 滚动升级期用 `CooperativeStickyAssignor` 并确认所有成员版本/配置一致（否则按交集退化 Eager）；② 让 `max.poll.records` 小到单批处理耗时远小于 `max.poll.interval.ms`，并保证处理线程内不再叠长耗时同步 RPC；③ 弹性伸缩一次别加太多实例——每加一个都要走一轮协议；④ 静态成员 `group.instance.id` 让 Pod 重启不改变成员身份，避免"重启即 rebalance"（classic 与新协议都支持，ConsumerMembershipManager L112 保留该字段）；⑤ 上 `group.protocol=consumer` 后别再手工调心跳/会话，交给服务端；⑥ 回滚预案里写死 `group.consumer.migration.policy`，避免混合视图。

## 6. 对照 RocketMQ：纯函数收敛 vs 有权威视图的收敛

两者都在解决"分区/队列到实例的独占映射如何随集群变化重算"，但**权威在哪**这一点上是两种哲学。

| 维度 | Kafka classic | Kafka KIP-848 | RocketMQ（push + client rebalance） |
|---|---|---|---|
| 分配是谁算的 | 组内 leader 成员 | coordinator 服务端 | **本实例自己算**（无 leader、无协调者） |
| 权威视图 | generation.id + 协议名 | target assignment epoch | 无——只保证"输入相同 + 函数相同" |
| 成员名单从哪来 | coordinator 组内登记 | coordinator 登记 | 向 broker 发 `GET_CONSUMER_LIST_BY_GROUP` 现查（RebalanceImpl L297 → MQClientInstance L1355-1370） |
| 触发方式 | 事件驱动（心跳应答里的 rebalance 标记） | 心跳应答带目标分配 | **独立线程周期轮询** 20s，未收敛退到 1s 快档（RebalanceService L25-30/L43-56）+ 心跳成功后 `rebalanceImmediately()` |
| 撤销语义 | 强制 revoke 回调，必须提交位点 | 成员向服务端确认后才替换 | 直接把 `ProcessQueue` 置 dropped，不回调业务（`updateProcessQueueTableInRebalance` L440-446） |
| 排他保证 | 服务端视图天然排他 | 同左 | 并发消费**不加锁**，靠收敛窗口容忍重叠；顺序消费才申请 broker 队列锁（RebalanceImpl L153-181，broker 租约 60s，RebalanceLockManager L33-34） |
| 空窗风险 | 屏障期内全组停 | 无屏障，逐成员收敛 | 各实例输入快照非同一时刻 → 短暂**重复消费**与**队列无人认领** |
| 大规模表现 | 屏障等待随组大小恶化 | 与组大小近似无关 | 每实例独立算，扩容不引入协商，但 RPC 查询成员名单的放大在 broker |

三条最值得记的差异：

**其一，Kafka 的 rebalance 是"有 generation 语义的协议"，RocketMQ 的是"最终一致的纯函数"。** RocketMQ 侧没有任何协调者、没有两阶段协商，`allocate(group, clientId, mqAll, cidAll)` 是幂等纯函数（RebalanceImpl L306-318），一致性完全押在"排序后的 mqAll/cidAll 对所有实例都一样"上（所以 L303-304 两次 `Collections.sort` 是协议正确性的一部分，不是代码风格）。代价是快照不原子：新实例刚入组、路由还没同步齐时，会出现同一个队列被两个实例各拉一份的窗口——并发消费容忍它，顺序消费用 broker 端 60s 租约的 `lockBatchMQ` 勉强收口（`isLockAllExpired`/`isLocked` 判过期，抢不到锁就 `rebalanceLater(500)` 重试）。Kafka 不会重复分配同一分区，代价是必须先停下再换手。

**其二，演进方向相同，终点却不同。** RocketMQ 5.x 也做了"把分配收归服务端"：`clientRebalance(topic)` 返回 false 时走 `getRebalanceResultFromBroker` → `queryAssignment` RPC，由 broker 统一算并把每个队列的 mode（PUSH/POP）带回来（RebalanceImpl L228/L345-375；开关 `DefaultMQPushConsumer.clientRebalance` L290 默认 true，顺序消费与广播强制客户端算，RebalancePushImpl L138-141）。这与 KIP-848 同向，但 RocketMQ 的 pop 模式根本不追求独占——队列不再"归属"某个实例，rebalance 这一概念被消解掉一半；Kafka 的分区所有权仍是硬不变量，只是收敛方式变增量了。

**其三，"stop-the-world"是 Kafka 独有的问题标签。** 讨论 RocketMQ 时很少谈"停顿"，因为它没有屏障——代价以"重复消费窗口"的形式出现，被位点提交与业务幂等吸收；Kafka 把停顿显式暴露在 `ConsumerRebalanceListener` 回调里，于是"回调写了多久"变成全组停顿时长的一部分，KIP-848 之后才缩小到"只影响正在交接的那个分区"。这也是为什么 Kafka 花三代协议拆屏障，而 RocketMQ 选择直接换消费模型（pop）绕开整个问题。

## 7. 验收问题（读完必须能答）

1. `session.timeout.ms` 和 `max.poll.interval.ms` 分别兜哪种故障？由哪个线程喂？为什么卡死在处理逻辑里不会触发前者？（进程死/心跳线程；业务线程卡死/应用线程——KIP-62 后心跳已独立）
2. classic 协议下 `JoinGroup.rebalanceTimeoutMs` 取的是哪个配置？（`max.poll.interval.ms`，GroupRebalanceConfig L53-57）
3. Eager 为什么必须"先撤销全部再入组"？`maybeMarkPartitionsPendingRevocation` 为什么只对 EAGER 生效？（避免"提交在途 + fetch 继续推进 position"造成接手者重放窗口）
4. Cooperative 为什么至少两轮？它对 assignor 施加了什么硬约束，违反的后果是什么？（第一轮只减法、第二轮只加法；leader 不得静默转手，否则 IllegalStateException）
5. KIP-848 靠什么替代全局屏障？（每成员 `assignment`/`targetAssignment` 双账 + target epoch 确认，逐分区收敛）
6. 上 `group.protocol=consumer` 后哪些配置/ API 失效？客户端 `RangeAssignor` 对应服务端哪个 assignor？（`session.timeout.ms`、`heartbeat.interval.ms`、`partition.assignment.strategy`、`enforceRebalance()`；`range`）
7. Kafka 4.0 已 GA，为什么 `group.protocol` 默认仍是 classic？何时默认翻转？（默认切换等于全量组迁移，且自定义客户端 assignor 无对等实现、rack-aware 未完工；5.0 客户端默认翻转，6.0 仅支持新协议）
8. 同一个队列被两个 RocketMQ 实例同时拉到，为什么不算 bug？Kafka 会出现同一分区两个消费者吗？（无协调者、快照非原子的设计代价，靠位点与幂等吸收；不会，除非分区 leader 变更期间的短暂双主）
9. RocketMQ 顺序消费为什么要 `lockBatchMQ`，租约多久？拿不到锁怎么处理？（客户端分配无权威视图，需 broker 侧排他；60s；`rebalanceLater(500)` 重试）
