# RocketMQ 集群与 Kafka 集群的区别

> 主题边界：本文对比两者的**集群组织方式**——元数据中心、路由注册与剔除、topic/分区的 broker 分布、复制与故障切换粒度、客户端容错、扩容语义。RocketMQ 侧结论均按本地 5.5 源码核实（行号随文标注）；Kafka 侧为 KRaft 时代的机制级描述。存储引擎内部（刷盘、主从复制链路、controller 选主实现）不在本文展开，见[刷盘与复制精读](rocketmq-刷盘与复制精读.md)。

## 1. 元数据中心：AP 路由登记处 vs CP 元数据日志

### 1.1 NameServer 的三个事实

**事实一：节点间无数据同步。** NameServer 的路由表（`RouteInfoManager` 的 topicQueueTable/brokerAddrTable/brokerLiveTable 等）是纯内存结构，节点之间没有任何复制、选举、一致性协议——每个 NameServer 都是独立的全量路由登记处，互相不知道对方存在。部署多个 NameServer 不是为了组成集群，而是为了**冗余**：挂一个，客户端换一个连。

**事实二：broker 注册到所有 NameServer。** broker 启动后周期性执行 `registerBrokerAll`（BrokerController L2003：初始延迟 10s，周期 `Math.max(10000, Math.min(registerNameServerPeriod, 60000))`，默认 30s，BrokerConfig L188），把自身地址 + 全量 topic 配置**逐个推送**给配置列表里的每一个 NameServer。路由数据是 broker 推上去的，不是 NameServer 拉下来的；NameServer 自己不产生任何数据。

**事实三：心跳超时剔除。** 每个 NameServer 独立扫描 `brokerLiveTable`（`scanNotActiveBroker`，RouteInfoManager L803；NamesrvController L119 调度，周期 `scanNotActiveBrokerInterval` 默认 5s，NamesrvConfig L55）：`lastUpdateTimestamp + heartbeatTimeoutMillis < now` 即关闭通道并摘除该 broker 的全部路由。超时默认 **120s**（`DEFAULT_BROKER_CHANNEL_EXPIRED_TIME = 1000 * 60 * 2`，L70；5.x 支持 broker 注册时自带超时值）。另有一条快路径：TCP 连接断开触发 `onChannelDestroy`，**立即**提交注销，不必等满 120s——120s 兜的是"连接还在但进程僵死"的场景。

三个事实合起来就是 NameServer 的一致性模型：**最终一致，且收敛靠 broker 的下一轮注册**。broker 刚启动时各 NameServer 的注册到达有先后；broker 僵死时各 NameServer 的剔除时刻也可能差一个扫描周期。窗口内不同客户端从不同 NameServer 拿到的路由可能不一致——NameServer 不解决这个问题，把它交给客户端容错（§4）。

### 1.2 Kafka：元数据是一条复制日志

Kafka（KRaft 时代）的 controller 组成 Raft quorum，**集群元数据本身是一条被共识复制的日志**：topic 创建、分区副本分配、leader 变更都是日志条目，多数派确认才生效。broker 向 controller 心跳并增量拉取元数据日志；客户端从**任意 broker** 获取元数据（broker 是 controller 日志的只读副本）。ZooKeeper 时代同理——元数据的权威在 ZK，controller 是唯一写入者。

| 维度 | RocketMQ NameServer | Kafka controller（KRaft） |
|---|---|---|
| 一致性模型 | AP：各节点独立，无同步 | CP：Raft 复制日志，多数派 |
| 数据来源 | broker 周期推送（30s） | controller 日志 + broker 心跳/拉取 |
| 节点角色 | 全对等，无 leader | quorum 内有 active controller |
| 故障判定 | 各节点独立按 120s 超时剔除 | controller 按 broker 心跳租约判定，变更走日志 |
| 不一致窗口 | 有（注册/剔除到达时差），客户端兜底 | 元数据变更线性一致；broker 侧缓存仍有传播延迟 |
| 实现复杂度 | 极低（无状态登记处，几千行） | 高（共识 + 日志复制 + 快照） |

NameServer 用"放弃强一致"换来了极低的运维与实现成本：它挂了不丢数据（broker 下一轮注册就重建），脑裂无所谓（路由本来就是各记各的）。Kafka 必须强一致，因为**leader 归属是排他性决策**——两个 controller 各认一个 leader 就是双写；而 NameServer 登记的只是"哪些 broker 声称能服务哪些 topic"，短暂多记/少记由客户端重试消化。

## 2. topic 是否跨 broker：都跨，不跨的是"单个队列"

先纠正一个常见误判："RocketMQ 的 topic 不跨 broker"——**不对**。RocketMQ 一个 topic 的队列同样分布在多个 broker 上，与 Kafka 的分区分布同形：

- **建 topic 就是逐 broker 建队列**。`mqadmin updateTopic -c cluster` 会在集群每个 broker 上各建一份 TopicConfig（每 broker 默认读写各 16 队列，TopicConfig L34-35）；自动创建走 TBW102 模板，队列数 = `min(客户端 defaultTopicQueueNums=4, TBW102 的 8)` = **每 broker 4 队列**（TopicConfigManager L289、DefaultMQProducer L111、BrokerConfig L50）。[发送链路精读](rocketmq-发送链路精读.md) §5.2 的实测正是单 broker 4 队列（queueId 0~3 轮转）——两 broker 集群就是 8 队列。
- **读写队列数可分开配，但稳态下两者相同**（`-n` 会一次设两值）。分开配主要服务于在线扩缩容的两阶段过渡：生产者按 QueueData 的 writeQueueNums 展开发送队列（MQClientInstance L305），消费者 rebalance 按 readQueueNums 展开分配集合（L328），两个数字是两条独立通路上的闸门。缩队列时先 `-w` 后 `-r`：若一步到位，被摘掉的队列里还有存量没消费完，readQueueNums 一缩这些队列就从消费者视图消失，消息变无人可读的"幽灵消息"；扩容方向对称，先扩读再扩写。
- **路由是按 broker 聚合的**。NameServer 返回的 TopicRouteData 里每个 broker 一条 QueueData（brokerName + 读写队列数），producer 把所有 broker 的队列展开成一个 MessageQueue 列表轮询发送——消息天然散到多个 broker。
- **不跨 broker 的是单个 MessageQueue**：一个队列只属于一个 broker，其消息物理上混写在该 broker 的 CommitLog 里（所有 topic 共享一条物理日志，ConsumeQueue 只是索引）。队列是顺序性与消费分配的最小单位，这一点与 Kafka"单个分区的 leader 只在一个 broker 上"同形。

真正的分布差异在**物理存储组织**与**副本**：

| 维度 | RocketMQ | Kafka |
|---|---|---|
| 逻辑分片 | MessageQueue，每 broker 独立建若干 | partition，建 topic 时统一分配到 broker |
| 分片归属 | 单队列单 broker | 单分区 leader 单 broker，**副本跨 broker** |
| 物理日志 | 全 broker 一条 CommitLog，所有 topic 混写 | 每分区独立 log 目录（segment 文件） |
| topic 很多时 | 仍是一条日志顺序写（高 topic 数优势来源） | 分区日志多路并发写，topic/分区数大时退化为随机写 |
| 分片数变更 | 各 broker 独立改队列数（影响顺序消息） | 分区只能增不能减，增分区破坏 key 路由 |

## 3. 复制与故障切换：broker 级 vs 分区级

这是两者集群语义差异最大的一层。

**RocketMQ：复制单位是整个 broker。** 主从复制的是整条 CommitLog 字节流（[刷盘与复制精读](rocketmq-刷盘与复制精读.md) §4），不区分 topic/队列——slave 是 master 的全量镜像，没有"某个 topic 单独配副本数"的概念。传统主从**不自动选主**：master 挂了，slave 只继续供给读（消费端从 slave 拉），写入侧靠 producer 重试切到**其他 broker 组**的队列；恢复写入要么人工切换，要么上 DLedger（broker 组内 Raft 自动选主）或 5.x controller 模式（[刷盘与复制精读](rocketmq-刷盘与复制精读.md) §7）。

**Kafka：复制单位是分区。** 每个分区独立配置副本数与 ISR，`acks=all + min.insync.replicas` 定义持久性口径；broker 挂了，controller 把它上面的所有 leader 分区**自动**切到各自 ISR 里的存活副本——故障切换是分区粒度、自动、秒级的。代价是 `unclean.leader.election` 的两难：ISR 全灭时要么不可用（保一致），要么选落后副本（保可用、丢数据）。

| 维度 | RocketMQ | Kafka |
|---|---|---|
| 复制粒度 | broker 组（整条 CommitLog） | 分区（每分区独立 ISR） |
| 副本配置 | 部署形态决定（一主一从/一主多从/DLedger 组） | 每 topic 一个 replication.factor |
| 自动选主 | 传统主从无；DLedger/controller 模式有 | 原生有，controller 主导 |
| master/leader 挂掉的写入 | 重试其他 broker 组（该组队列暂不可写） | 分区 leader 切换后原地恢复 |
| 持久性口径 | SYNC_FLUSH / SYNC_MASTER（刷盘与复制精读 §2~4） | acks + min.insync.replicas |
| 读扩展 | slave 可读（消费端自动降级拉 slave） | follower 默认不供读（KIP-392 允许就近读） |

一个推论：RocketMQ 里"某 broker 组全挂"丢的是**该组队列的写入能力**，topic 整体仍可写（其他组的队列还在）；Kafka 里"某分区 ISR 全挂"丢的是**该分区的可用性**，topic 整体仍可写（其他分区还在）。两者都用"分片冗余"换整体可用，只是分片的复制组织方式不同。

### 3.1 KRaft 不在消息数据路径：acks 等的是 ISR 追平，不是过半投票

易混点澄清："发消息要等 KRaft 多数派确认才返回"——**不对**。KRaft 的 Raft 组只复制低频元数据（§1.2），消息数据走完全独立的一条路：producer 从任意 broker 拿元数据定位分区 leader → 发往 leader → leader 顺序 append 本地 log（进 page cache，ack 不要求 fsync）→ follower 持续从 leader 拉取（fetch）→ 按 acks 口径回 ack。一条消息只进**一个分区**，复制发生在该分区的几个副本之间，不存在"消息写到多个分区"。

| acks | leader 何时回 ack | 持久性 |
|---|---|---|
| 0 | 不等，发出去就算 | 可能丢 |
| 1 | 本地 append 完成即回 | leader 挂且未复制出去就丢 |
| all（-1，3.0 起默认） | **ISR 里所有副本**都追到这条消息的 offset（HW 推进过它）才回 | ISR 内不丢 |

两处语义钉死：`acks=all` 等的是 **ISR 全员**确认，不是 Raft 式"过半"确认；`min.insync.replicas` 也不是投票门槛，而是**准入下限**——ISR 成员数掉到阈值以下时 leader 直接拒写（NotEnoughReplicas），宁可不可用也不降级持久性。

为什么这样性能并不差：它**不是每条消息跑一轮共识**，而是主备复制 + 流水线——follower 是持续拉取的长流水，leader 只维护每个 follower 的 LEO 并比较 offset，复制往返被管道化摊平（同机房下 all 通常只比 1 多零点几到几毫秒）；producer 端攒批（linger.ms/batch.size）+ 压缩让一次 fetch 拉走一大段，单条消息的复制成本被整批摊薄；leader 本地顺序 append，持久性押在副本冗余而非每节点落盘。真正贵的共识（多数派 + 落盘）只发生在元数据变更这种低频路径上。RocketMQ 同构：主从复制也是整条 CommitLog 字节流推送而非逐条共识（§3 正文）；DLedger 模式才把 Raft 引入数据面，代价就是吞吐下降。

这就是 Kafka 的分层取舍：**元数据用 CP（低频、要强一致），数据面用可调持久性的主备复制（高频、要吞吐）**——若每条消息都走 Raft（多数派确认 + 各节点 fsync），吞吐才会真的崩。

### 3.2 三种 leader：broker 全对等 vs RocketMQ 的 broker 级 master

"leader"一词在 Kafka 里指三种完全不同的角色：

| 角色 | 粒度 | 职责 | 数量 |
|---|---|---|---|
| 分区 leader（数据面） | 每分区一个 | 该分区唯一读写入口，follower 从它 fetch | 全集群成千上万，**散在所有 broker** |
| active controller（控制面） | 集群一个 | 元数据权威（§1.2），KRaft quorum 的 Raft leader 担任 | 1 |
| consumer group leader（客户端） | 每消费组一个 | rebalance 时执行分配算法（assignor），结果交 coordinator broker 下发 | 组内某个普通 consumer |

平时说"Kafka 的 leader"默认指第一种。**数据面没有"leader server"**：broker 全对等，每台同时对一部分分区是 leader、对另一部分是 follower——leader 是"帽子"不是"身份"。controller 建 topic 时按副本列表排布（列表首个是 **preferred leader**），目标是各 broker 的 leader 数均衡；故障切换会打破均衡（挂掉 broker 的帽子全扣到存活副本上），所以有 preferred leader election（`auto.leader.rebalance.enable`）周期性把帽子还回去，`leader.imbalance.per.broker.percentage` 度量倾斜。

**RocketMQ 恰好是镜像**：传统主从的 master 是 **broker 组级身份**——整台 broker 的整条 CommitLog 是主，slave 是全量镜像，"主"是部署时定死的节点角色，接近"leader server"，但作用域是一个 broker 组而非全集群，各组互相独立无上下级；Kafka 的 leader 则是分区级帽子，故障时按分区粒度重新扣帽子——这正是 §3 正文复制粒度差异的直接推论。

## 4. 客户端视角：路由获取与容错

**RocketMQ**：producer/consumer 每 30s 从 NameServer 拉一次路由（`pollNameServerInterval`，ClientConfig L58）并缓存本地。由此得到两条运维性质：

1. **NameServer 全挂不影响存量收发**——客户端用本地路由缓存直连 broker，新客户端才起不来；
2. **路由陈旧是常态**，容错做在发送侧：失败重试换队列（默认避开上次失败的 broker）+ `MQFaultStrategy` 延迟规避（[发送链路精读](rocketmq-发送链路精读.md) §1.3）。NameServer 剔除 broker 不通知任何人，客户端最坏要等 30s 轮询或一次发送失败才知道。

**Kafka**：客户端从任意 broker 拉元数据，leader 切换后 broker 返回 `NOT_LEADER_OR_FOLLOWER` 等错误码，客户端刷新元数据重试——路由纠错是**请求驱动**的，比 RocketMQ 的周期轮询收敛快。controller 全挂同样不影响存量收发（数据面不经过 controller），但选主、建 topic 等元数据变更停摆。

### 4.1 消费端分配：分片数是并行度天花板

两者同一条定律：**消费并行度上限 = 分片数**，实例数超过分片数就是浪费。

**RocketMQ**：分配单位是 MessageQueue，**broker 边界被抹平**——rebalance（RebalanceService，默认每 20s，实例上下线/broker 增减也触发）从路由把所有 broker 的队列展开成一个排序列表，与组内排序后的 consumerId 一起交给分配策略。以"2 broker × 各 16 队列 = 32 队列"为例，默认 `AllocateMessageQueueAveragely`（L37-45）做连续切片：`mod = 32 % N`，前 mod 个实例各分 ⌈32/N⌉ 个，其余各分 ⌊32/N⌋ 个：

| 实例数 N | 每实例队列数 |
|---|---|
| 1 | 32（一个实例吃全部，完全合法） |
| 16 | 2 |
| 32 | 1（并行度拉满） |
| >32 | 多余实例分不到队列，空转（集群模式） |

实例数按吞吐需求定，不需要对齐"每 broker 16 个"；每个实例对自己分到的队列各起长轮询拉取，通常同时连着多台 broker。两个变体：顺序消费加 broker 端队列锁（同一队列同一时刻只被一个实例消费，上限不变）；广播模式每实例消费全部队列，不存在分配。位点存 broker 端。

**Kafka**：rebalance 按分区分配（assignor 由消费组 leader 执行，§3.2），上限同样是分区数；位点存 `__consumer_offsets`（也是一个 Kafka topic）。差别在抬天花板的成本：RocketMQ 队列是轻量索引，建 topic 时调大每 broker 队列数即可近乎零成本抬上限；Kafka 加分区是加物理日志，且破坏按 key 路由（§2 表）。

## 5. 扩容：加节点 ≠ 数据迁移

- **RocketMQ 加 broker**：新 broker 注册进 NameServer，在其上建 topic 队列后，producer 下一轮路由刷新即开始向新队列写入。**存量数据不迁移**——老消息留在老 broker，消费端把新队列纳入 rebalance 即可。缩容则要先禁写（perm 去掉写权限）等存量消费完。
- **Kafka 加 broker**：新 broker 加入后**不会自动承接负载**，存量分区副本还在老 broker 上；要显式做 partition reassignment（或 Cruise Control）**物理搬迁数据**。这是"分区副本分配是建 topic 时一次性决策"的直接代价。

差异的根源仍是 §2 的物理组织：RocketMQ 的队列是轻量索引，新 broker 建新队列零成本；Kafka 的分区是带副本的物理日志，负载再平衡必须搬日志。

## 6. 收束

| 问题 | RocketMQ | Kafka |
|---|---|---|
| 元数据节点间同步吗 | 不同步，broker 注册到所有节点 | 同步，Raft 复制日志 |
| broker 失联谁摘除 | 各 NameServer 独立按 120s 超时摘路由 | controller 判定，leader 变更走日志 |
| topic 跨 broker 吗 | 跨（每 broker 各建队列） | 跨（分区分配到多 broker） |
| 什么不跨 broker | 单个 MessageQueue | 单个分区的 leader |
| 复制单位 | broker 组整条 CommitLog | 分区 ISR |
| 自动故障切换 | 传统主从无（DLedger/controller 有） | 原生分区级自动选主 |
| 扩容搬数据吗 | 不搬，新队列接新写入 | 要显式 reassignment |

**验收问题**（读完必须能答）：

1. 为什么 NameServer 敢不做节点间同步，而 Kafka controller 必须做共识复制？（提示：登记"谁能服务" vs 决策"谁是唯一 leader"）
2. broker 进程僵死但 TCP 连接未断，NameServer 最坏多久摘除它？连接正常断开呢？（120s + 扫描周期；立即）
3. 两 broker 集群自动创建 topic，producer 看到的 MessageQueue 列表有几个元素？（8 = 每 broker min(4,8)）
4. RocketMQ master 挂掉后写入为什么还能继续？Kafka 分区 leader 挂掉后呢？（重试其他 broker 组的队列；ISR 内自动选主）
5. 给 RocketMQ 集群加一台 broker，老消息会挪过去吗？Kafka 呢？（不会；要显式 reassignment）
6. Kafka `acks=all` 时 leader 等的是什么？KRaft 参与吗？`min.insync.replicas=2` 挡的是什么？（ISR 全员追平该 offset；不参与，只管元数据；ISR 缩到 2 以下时拒写，是准入下限不是投票数）
7. Kafka 有几种 leader？数据面有 leader server 吗？RocketMQ 的 master 是什么级？（分区 leader / active controller / 消费组 leader 三种；没有，broker 对等、leader 按分区散布；broker 组级，整条 CommitLog 为主）
8. 2 broker × 各 16 队列，16 个消费实例每实例分几个队列？多少实例拉满并行度？（2 个；32 个，再多空转；分配按展开后的 32 队列切，与每 broker 16 个无关）