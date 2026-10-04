# RocketMQ 消费链路精读：Push 模型 × 集群消费（5.5.1）

> 范围：只读 push（DefaultMQPushConsumer，实为长轮询拉）× 集群模式（CLUSTERING，位点在 broker）× MessageListenerConcurrently。POP 消费、广播、顺序消费不在主线，必要时一句话对照。
> 样本：quickstart 消费者（`CONSUME_FROM_FIRST_OFFSET`、`subscribe("TestTopic","*")`、`MessageListenerConcurrently`、group `quickstart_consumer_group`）。
> 口径：行号对照本机 `~/github/rocketmq`（5.5.1-38）逐行核验；姊妹篇《发送链路精读》《刷盘与复制精读》交叉引用处以 §前缀标明。
> 一个先立的世界观：**RocketMQ 的"push"是客户端 API 的幻觉**——没有任何线程从 broker 推消息进 listener；全部是客户端自己调度 pullRequest、拉回后本地回调 listener。所以消费链路的主语永远是 PullRequest。

阅读顺序（一个消费者从 start 到第一条消息进 listener 的时序）：

```
① 启动装配   DefaultMQPushConsumerImpl.start → MQClientInstance（任务族+两大服务线程）
              → 首次 rebalance → computePullFromWhere（ConsumeFromWhere 生效点！）
              → dispatchPullRequest：第一颗种子
② 拉取引擎   PullMessageService 循环 → 流控闸门群 → MQClientAPIImpl.pullMessage ── TCP ──►
③ broker 应答 PullMessageProcessor → DefaultMessageStore.getMessage（⑥篇 §6.4 已读过）
              → transferMessage 零拷贝 / 长轮询 PullRequestHoldService 挂起-唤醒
④ 回包与消费 PullAPIWrapper.processPullResult → ProcessQueue(TreeMap)
              → ConsumeMessageConcurrentlyService → 你的 consumeMessage() → ack → offset 推进
⑤ 位点闭环   RemoteBrokerOffsetStore 内存表 + 5s persistAllConsumerOffset
              → broker ConsumerOffsetManager/consumerOffset.json（⑥篇 6.4 的账本收到存款）
```

---

## ① 启动装配：从 start() 到第一颗 PullRequest

### 1.1 `DefaultMQPushConsumerImpl.start()`（L923）装配九步

| 步 | 行号 | 干什么 | 值得注意 |
|---|---|---|---|
| 校验/拷贝订阅 | L930-932 | `checkConfig` + `copySubscription`（用户 subscribe 进内部表） | 一个实例内 group 名重复直接拒 |
| **CLUSTERING 专属** | L934-936 | `changeInstanceNameToPID()` | 集群模式把 instanceName 改成 **PID**——clientId 必须每进程唯一，rebalance 才认得出"我"；广播不改（本地位点文件按 clientId 分目录，撞了会共享文件，这是广播用户的责任） |
| 拿/建工厂 | L938 | `MQClientManager.getOrCreateMQClientInstance` | 按 clientId 复用——同 JVM 第二、三、N 个 consumer 共享一个 MQClientInstance（心跳/路由/拉取线程全共用） |
| 装 rebalance/pullAPI | L940-950 | 注入 group/strategy/factory | `allocateMessageQueueStrategy` 默认**平均分配** |
| **选 OffsetStore** | L952-967 | CLUSTERING → `RemoteBrokerOffsetStore`（L960） | 广播才走 LocalFile（L957）——⑥篇 6.4 两本账在这一行的分岔；L967 `load()` 集群版只清表不发 RPC |
| **选消费服务** | L969-982 | 按 listener 类型 | 我们的 `MessageListenerConcurrently` → `ConsumeMessageConcurrentlyService`（L977）；顺带看：5.x 还把 POP 变体也 new 了（L980），push 路径不用它——两套服务并存，POP 蚕食 push 的过渡形态 |
| 注册进工厂 | L988-994 | `registerConsumer(group, this)` | 同 JVM 同 group 二次注册抛 "has been created before"（L992） |
| 工厂启动 | L997 | `mQClientFactory.start()` | 见 1.2 |
| **点火三连** | L1013-1016 | 更新订阅版路由 → `checkClientInBroker` → `sendHeartbeatToAllBrokerWithLock()` 成功才 `rebalanceImmediately()`（L1015-1016） | 第一次 rebalance 是**事件触发**，不等周期——心跳即注册，注册完立刻分队列 |

### 1.2 `MQClientInstance.start()`（L338-376）：起什么、多久跑一次

```
fetchNameServerAddr(未配地址才需要, L346-347) → mQClientAPIImpl.start(L350)
→ startScheduledTask(L352) → pullMessageService.start(L354) → rebalanceService.start(L356)
→ 内建 defaultMQProducer.start(false)(L358)
```

- 注释叫 "Start push service" 的 L358 其实起的是**工厂自带的 producer**——心跳、注册 offset、查位点这些客户端管理面 RPC 全借它的通道。**消费者自己不发 RPC，管理面走内建 producer**，这是个很少被提起的装配事实。
- 定时任务族（`startScheduledTask` L389-432，默认值 ClientConfig 实证）：

| 任务 | 周期 | 默认值行号 |
|---|---|---|
| fetchNameServerAddr | 2min（仅未配 addr） | L390-397 |
| 刷 topic 路由 | `pollNameServerInterval` **30s** | ClientConfig L58 |
| cleanOfflineBroker + **心跳注册**（携带订阅关系→broker 由此认识这个 group） | `heartbeatBrokerInterval` **30s**，首次 1s 后 | L62 |
| **persistAllConsumerOffset（提交位点）** | `persistConsumerOffsetInterval` **5s**，首次 10s | L66——⑥篇 6.4 broker 侧 5s 落盘，客户端侧 5s 上交，两头节拍巧合地同数 |
| adjustThreadPool（消费线程池按堆积自适应） | 1min | L425-431 |

- **rebalance 不是定时任务，是独立线程**（RebalanceService L356 起）：`run()` 里 `waitForRunning(realWaitInterval)`，`waitInterval` 默认 **20s**（system property `rocketmq.client.rebalance.waitInterval`，L25-27），`minInterval` 1s（L28-30）；被 `rebalanceImmediately()` wakeup 时若距上次不足 1s，会**补足 minInterval 再跑**（L48-50）——所以 1.1 点火那次"立即 rebalance"实际最快 ~1s 后执行；`balanced` 结果还决定下轮间隔（未收敛就回到快档）。另一头 `rebalanceLater(500)`（MQClientInstance L1225）给锁不齐队列的场景留了延时补刀。

### 1.3 首次 rebalance 怎么播下第一颗 PullRequest（RebalanceImpl L426）

`doRebalance → rebalanceByTopic`（route 拿 mqSet → `strategy.allocate` 分给本 clientId）→ **`updateProcessQueueTableInRebalance` L426** 三段：

1. **删旧**（L440-461）：不在名下的 / `pullExpired` 的 pq 置 dropped 并 `removeUnnecessaryMessageQueue`——注意 L442-446 那条 `[BUG]...try to fixed it`：**拉取停摆超过阈值会主动扔掉队列重建**，rebalance 兼职做自愈，push 消费端的心跳就是这 20s 一轮。
2. **加新**（L463-497）：`removeDirtyOffset` → `createProcessQueue` → **`computePullFromWhere(mq)` L477** → 组装 `PullRequest{group, nextOffset, mq, pq}`（L484-489）。
3. **派发**（L499-503）：有没锁齐的 → `rebalanceLater(500)`；`dispatchPullRequest(list, 500)` → RebalancePushImpl L261-267：这批是新增队列（非 rebalance 中途换主），**逐个 `executePullRequestImmediately` 投进 PullMessageService 的 `pullRequestQueue`**——第一颗种子落地，②篇接力。

### 1.4 `ConsumeFromWhere` 的真实生效条件（最容易记错的一格）

`computePullFromWhereWithException`（RebalancePushImpl L155 起）——**所有 case 第一步都是 `offsetStore.readOffset(mq, READ_FROM_STORE)` 问 broker 要已提交位点**（L174/L197 分支入口）：

- 要到了（该 group 在这队列消费过）→ **直接续读，`ConsumeFromWhere` 形同不存在**；
- 要不到（首次）才按策略：`CONSUME_FROM_FIRST_OFFSET → minOffset`（我们样本的路径，且 L253 有一处 FIRST 特判）；`CONSUME_FROM_LAST_OFFSET`：新队列→min，老队列→max（跳过历史积压）；`CONSUME_FROM_TIMESTAMP` → `searchOffset(mq, timestamp)`（L226，按时间查 index——⑥篇 §5.4 那个哈希索引文件在消费链路的第一个下游用户）。

> **一句话**：`ConsumeFromWhere` 是"初见策略"不是"重启策略"——重启续消费走位点账本，清账本或换 group 才轮到它出场。

### 验收问题（读完 ① 必须能答）

- [ ] 为什么 CLUSTERING 要 `changeInstanceNameToPID` 而广播不？广播撞 instanceName 的真实后果是什么？
- [ ] 同 JVM 起两个不同 group 的 push consumer，会有几套心跳/几个 PullMessageService 线程？谁共享谁独占？
- [ ] start 后第一条消息大概几秒内能被拉？列出路上所有节拍器（rebalanceImmediately→minInterval 1s→dispatchImmediately→pullMessage 排队…）。
- [ ] 消费组重启后想"重新消费全部历史"，除清 broker 位点外的合法手段是什么？（`resetOffsetByTime`/CONSUME_FROM_FIRST_OFFSET 为什么不生效）
- [ ] 心跳为什么能触发 rebalance？心跳体里带的什么让 broker 认识这个 group，从而出现在"在服消费者列表"里影响别的 consumer 的分配？
- [ ] L442 的 `pullExpired` 自愈：什么样的 pq 会被判过期？这个机制防的是哪种事故？

---

## ② 拉取引擎：一台永不出网的调度线程 + 七道闸门 + 自我续装循环

### 2.1 5.5.1 的形态：PullRequest/PopRequest 合并成消息总线

`PullMessageService`（全类 157 行）：

- 一个无界 `LinkedBlockingQueue<MessageRequest>`（L32）——**PullRequest 和 PopRequest 都实现 `MessageRequest` 接口**，run 循环 `take()` 后按 `getMessageRequestMode()` 分派 POP/PULL（L131-137）。4.x 时代的 `pullRequestQueue.take()` 没了，pull/pop 共用一台调度器。
- 一个专属 `scheduledExecutorService`（L35，"PullMessageServiceScheduledThread"）——`executePullRequestLater` 延迟投回主队列（L47-54），到期动作还是 `executePullRequestImmediately`（L56-63 `messageRequestQueue.put`）。
- `pullMessage(pr)` 本体只是查 group→impl 再转调（L114-119）——**闸门逻辑都在 `DefaultMQPushConsumerImpl.pullMessage`**，全本地检查，**网络一律 ASYNC 回调**（L492 `CommunicationMode.ASYNC`）。这台调度线程从头到尾不出一次网、不 park 在任何 IO 上——与复制专题篇 §4"传输与等待解耦"同一哲学，也呼应 broker 端 `asyncPutMessage` 释放发送线程。

### 2.2 闸门群（`DefaultMQPushConsumerImpl.pullMessage` L246 起，顺序即优先级）

| # | 闸门 | 默认值/常量（已验） | 触发后的动作 |
|---|---|---|---|
| 1 | `processQueue.isDropped`（L248） | — | **丢弃不续装**（rebalance 已收回该队列，静默终接） |
| 2 | 记 `lastPullTimestamp`（L253） | — | 给 ①1.3 的 `pullExpired` 自愈喂表——闸门1 和这道"打卡"是配对的 |
| 3 | `makeSureStateOK`（L256） | — | 异常延迟重试（`pullTimeDelayMillsWhenException`，可配） |
| 4 | `isPause()`（L263，suspend API） | 延迟 1s（L113） | 人工刹车 |
| 5 | 条数缓存 `pullThresholdForQueue`（L272） | **1000 条**（Consumer L185） | 延迟 **50ms** 重投（L105）+ 每 1000 次限流打一条 warn |
| 6 | 体积缓存 `pullThresholdSizeForQueue`（L282） | **100 MiB**（L200） | 同上 |
| 7 | 并发专属：`getMaxSpan()`（L293） | `consumeConcurrentlyMaxSpan=2000`（L179） | 同上——TreeMap 首尾 offset 跨度封顶：并发消费允许乱序 ack，但若最老一条迟迟不 ack，窗口内堆积会无界，**span 是"提交位点被卡住"的间接测度** |
| — | 顺序专属（L303-332，本线跳过） | — | orderly 查 pq.isLocked、首拉 `computePullFromWhere` 校正 nextOffset、brokerBusy 告警 |

注意 5/6/7 的处罚是 **50ms 重投**而不是丢弃——拉取"饿"而不是"死"，消费推进后 50ms 内就能恢复全速。①表里 `pullThresholdForTopic` 就是在这被均摊成 per-queue 值（RebalancePushImpl.messageQueueChanged L65-79）。

### 2.3 拉取请求的组头：位点搭车 + 订阅版本 + 长轮询双时限

L453-494 `pullKernelImpl` 实参里三个值得停下的点：

1. **位点搭拉取的车**（L453-460）：CLUSTERING 下读 `READ_FROM_MEMORY`，>0 就置 `PullSysFlag` 的 commit 位并带 `commitOffsetValue`——**位置上报不止 5s persist 一条路，每个 pull 请求头都可能顺带**（broker 端消不消费这个 flag，③验证）。四元 sysFlag：commit / suspend / sub / classFilter（L473-478）。
2. **订阅默认不随 pull 走**（L466，`postSubscriptionWhenPull=false` Consumer L244）：broker 的 tag 过滤用的是**心跳注册的订阅关系**（带 `subVersion`，pull 时校验——对不上返回 `SUBSCRIPTION_NOT_LATEST`，callback L438 专门认这个码）；①验收题 5 的答案就在这：心跳注册订阅 → pull 只报版本号。**同 group 不同订阅的"订阅不一致"事故，拦截点就是这里。**
3. **长轮询双时限**（L490-491）：`BROKER_SUSPEND_MAX_TIME_MILLIS=15s`（挂起上限）+ `CONSUMER_TIMEOUT_MILLIS_WHEN_SUSPEND=30s`（客户端 RPC 超时）——30 > 15 留足网络余量，**挂起发生在 broker，不发生在客户端**。
4. 拉取体量：`pullBatchSize=32` 条 / `pullBatchSizeInBytes=256KB`（L237/239，broker 端取先到者）。

### 2.4 回调状态机：一台自我续装泵（L345-451）

```
onSuccess → processPullResult（PullAPIWrapper，⑥篇 6.2 见过的 decodesBatch 在此）
  FOUND      : nextOffset=nextBeginOffset；空列表→立即续装；
               有货→ pq.putMessage + submitConsumeRequest(→④) → 立即续装（pullInterval=0 默认，L376-381）
  NO_NEW_MSG /
  NO_MATCHED : 推进 offset + correctTagsOffset + 立即续装（← 长轮询的客户端半边就是"立即再发"，
               因为 broker 已把这一发挂了 15s，回来即续，形成"推"的手感）
  OFFSET_ILLEGAL（L402-428）:
     接受 nextBeginOffset → pq.dropped → 异步任务四连：updateAndFreezeOffset + persist +
     removeProcessQueue + rebalanceImmediately
     ← ⑥篇 6.4 "钳位三元组" 的客户端收口：broker 把越界位点钳回来，客户端连本地缓存一起冻结落盘，
       再把队列扔回 rebalance 重建——又是"坏状态整个扔掉重来"，与 §4.5 重连自愈同套路
onException  : broker 侧 FLOW_CONTROL → 20ms 轻惩罚（L109）；其他异常 → 可配延迟重投
乱序哨兵     : nextBeginOffset/firstMsgOffset 比本次请求还小 → [BUG] warn（L384-391，只报警不处理）
```

### 2.5 为什么"无节拍"是正确设计

`pullInterval=0`（L227）默认下，FOUND 与空回都**立即续装**——队列的并发度由 PullRequest 这个"令牌"自身控制：**每个 processQueue 同时在路上最多一个 pull**（拉完才续下一发），天然反压、不需要任何定时器。吞吐来源=队列数×(15s 长轮询挂起→即时返回)。这也解释了 ① 验收题 3 的时间预算：start→首拉 ≈ 心跳(1s 首发)+rebalance(minInterval 1s) 量级，不是 20s。

### 验收问题（读完 ② 必须能答）

- [ ] 闸门 1（dropped）静默丢弃、闸门 5/6/7 延迟 50ms——两种"不拉了"的本质区别？各防什么事故？
- [ ] `maxSpan=2000` 为什么只在并发模式检查？顺序模式用什么机制顶替它？（locked + 不超 batch 提交）
- [ ] 位点上报一共几条路？（答案在 ⑤：pull 顺带 + 5s persist + shutdown 补交；orderly **不**走 unlock 携带，它提交进同一本 offsetTable）pull 头的 commit 和 5s persist 谁先谁后、会不会互相覆盖？（提示：`ControllableOffset` 的 increaseOnly/freeze 语义）
- [ ] `SUBSCRIPTION_NOT_LATEST` 从 broker 视角什么时候产生？客户端为什么只 warn 不重试？
- [ ] OFFSET_ILLEGAL 处理里为什么必须先 `setDropped` 再异步四连，而不是就地改 nextOffset？（提示：pq 里可能已有在途消息/在途 ack）
- [ ] 长轮询的"15s 挂起"发生在哪台机器的什么结构上？客户端 30s 超时和它的关系？（③见分晓：PullRequestHoldService）

---

## ③ broker 应答：双账位点、零拷贝传输、事件+兜底双驱动的长轮询

### 3.1 `PullMessageProcessor.processRequest`（L290）主干

```
解码 header（PULL_MESSAGE / V2 / LITE_PULL_MESSAGE L325 开关校验）
→ 组权限：subscriptionGroupConfig 缺失→GROUP_NOT_EXIST（L333-341，consumeEnable=false 也拒）
→ 订阅一致性：用【心跳注册】的 SubscriptionData；postSubscriptionWhenPull=true 才从 pull 请求解（L396）
   subVersion 落后 → SUBSCRIPTION_NOT_LATEST（L456）——②预告的拦截点在此
→ 静态 topic mapping 重写 → messageStore.getMessageAsync(...)（L566）→ thenApply 交 handler → 返回 null（不占 processor 线程）
```

- 拉取本体与 ② 对称：**5.5 的 broker 拉路径也全异步化**——`getMessageAsync` 起 future，结果处理在 `DefaultPullMessageResultHandler`（L68）里完成，`thenAccept(writeResponse)`。netty/biz 线程都不等盘。
- 顺带一个少见的知识点：slave 收到 pull 且 `forwardPullMessageToMaster` 时，转发给 master 前会**清掉 suspend 和 commitOffset 两个 flag**（L136-137）——长轮询挂起和位点搭车都是 master 专属语义，中转层不许携带。

### 3.2 handler 的账本动作：一发 pull 同时记两本账（L790-803）

```java
tryCommitOffset(...)
  ├─ commitPullOffset  → pullOffsetTable（transient！L54，不落盘）
  └─ commitOffset      → ConsumerOffsetManager.offsetTable（⑥篇 6.4 那本账）
        条件：brokerAllowSuspend && hasCommitOffsetFlag
```

- **②的悬念落地：pull 头搭车的位点真的会被 broker 消费**——和 5s `UPDATE_CONSUMER_OFFSET` RPC 进同一本账。两路的定位：pull 搭车=顺手报、persist=定时兜底；写的是同一个 `topic@group→queueId→offset`。
- `pullOffset` 与 `commitOffset` 是**两码事**：前者"拉到哪"（transient，重启即清零），后者"消费承诺到哪"。admin 侧 `consumerProgress` 用 `max(consumerOffset, pullOffset)` 拼 lag（AdminBrokerProcessor L2098-2103）——**"已拉未 ack"的积压只有 pull 账能照出来**，这正是排障时"堆积了但位点在动/位点不动"分野的数据来源。
- 账本动作之后才是响应组装（composeResponseHeader：nextBeginOffset + suggestWhichBrokerId L691-695——消费太慢时 broker 建议"下次去从库拉"，主备读写分离的小机关）。

### 3.3 SUCCESS 回包：两条传输路径（handler L143-175）

- `channelIsWritable` 先查 socket 写缓冲：不可写 → `getMessageResult.release()` + **返回 null 直接丢弃这次响应**（L143-146）——慢消费者不配继续占用页缓存引用，等它的下一次 pull。
- **默认 `transferMsgByHeap=true`**（BrokerConfig L134）：把 mmap 切片 read 进堆 byte[] 再 setBody——一次拷贝换实现简单。
- 关掉后走**真零拷贝**：`ManyMessageTransfer`（FileRegion）把 `GetMessageResult` 的 mmap 切片集合直接 `writeAndFlush` 到 socket，listener 里才 `release()`（L154-171）。**页缓存 → 网卡，跳过用户态**——⑥篇里 transferMessage 的位置在这里，消息存储布局（记录自带物理长度）保证了一个 GetMessageResult 就是可整段搬走的字节区段。

### 3.4 长轮询：`PullRequestHoldService`——事件驱动为主、5s 扫描兜底

`PULL_NOT_FOUND` 且 `brokerAllowSuspend && hasSuspendFlag`（handler L172-187）：

1. 包一个 **broker 侧同名 `PullRequest`**（request+channel+挂起截止+订阅快照），`suspendPullRequest(topic, queueId, pr)`——挂进 `pullRequestTable: "topic@queueId" → ManyPullRequest`，并给原 request 打 `setSuspended(true)`（HoldService L50-57）。**注意：Netty 的 channel 就这样被"晾"着不回包**，15s 的挂起发生在 broker 的这张表里，不是任何线程在 sleep。
2. **唤醒主路 = 派发事件**：ReputMessageService 每派发一条，`notifyMessageArriveIfNecessary`（store L2644-2652，`isLongPollingEnable` 门）→ `MessageArrivingListener.arriving(topic, qid, cqOffset+1, tagsCode...)` →（listener 由 BrokerController L417 构造、plugin context 注入 store，扇出含 holdService/pop/notification/liteEvent）→ HoldService 比对表中请求 offset ≤ 新到达位点 → `executeRequestWhenWakeup`。
3. **唤醒的再处理**（processor L806+）：把原 request **重跑一遍 `processRequest(channel, request, false, ...)`**——第三参 `brokerAllowSuspend=false`：**唤醒后的复查绝不再挂起**，要么有货回 SUCCESS，要么空手回 PULL_NOT_FOUND，一次了断。
4. **兜底路**：HoldService 自己的线程 `waitForRunning(5s)` 周期扫超时（run L71-75），到期同样走 executeRequestWhenWakeup——事件丢了也保证 15s+5s 内必回，客户端永远等得到响应。
5. 关掉 `longPollingEnable` 则挂起时长缩为 `shortPollingTimeMills=1s`（BrokerConfig L117-119）——退化为"1s 一轮的伪长轮询"。

> **为什么在 Reput 之后才 notify（正确性关键）**：拉取取数走 ConsumeQueue 索引；若在 append 进 commitlog 时就唤醒，复查照样 NO_NEW_MSG，等于空转。"派发完成才通知"把长轮询的语义精确到**"可消费的索引已就绪"**——⑥篇 §5.5"写不等索引、读等索引"的母题在此合龙。

### 3.5 位点越界的应答侧（与②的客户端收口配对）

⑥篇 §6.4 验过 store 的钳位（`OFFSET_TOO_SMALL→minOffset` 等）；broker 把"钳好的 nextBeginOffset"放进 `PULL_OFFSET_MOVED` 响应（handler L195+，主库或 `offsetCheckInSlave` 才检查）并抛 `OffsetMovedEvent`。客户端 ② 的 OFFSET_ILLEGAL 四连（冻结落盘→扔队列→rebalance）就是这句话的另一半。**一次钳位，两端各自自愈。**

### 验收问题（读完 ③ 必须能答）

- [ ] 一个 pull 请求在 broker 上产生几处位点写？各写到哪本账、什么条件？为什么 pull 账是 transient？
- [ ] `transferMsgByHeap` 默认 true 还是 false？关掉后零拷贝的具体机制是什么，release 时机挪到了哪里？
- [ ] 长轮询挂起的 15s 里，broker 有没有任何线程在为这个请求等待？没有的话，"等待"的实体是什么？
- [ ] 唤醒重入时为什么强制 `brokerAllowSuspend=false`？若允许再挂起会出什么事？
- [ ] 消费明显堆积但 commitOffset 一直在动——现在你能解释这可能是什么状态吗？（pullOffset/commitOffset 两本账 + 在途未 ack）
- [ ] 从库转发的 pull 为什么要清 commitOffset flag？不清会错在哪？

---

## ④ 回包消费：解码、双时间戳缓存、前缀 ack——以及三个常见误区

### 4.1 `PullAPIWrapper.processPullResult`：响应体五道工序

（执行线程：remoting 回调完成处——与 broker 端 `executeInvokeCallback` 同构的**客户端 netty 线程**；真正换线程是在 4.3 投递消费池之后。**这就是 pullBatchSize=32 保守的原因之一：解码 32 条也发生在 IO 线程上**。）

1. 位点起点回写：`updatePullFromWhichNode(mq, suggestWhichBrokerId)`——③ 3.2 那个"消费慢建议去从库"的提示，在这里变成**下一发 pull 的目标节点**。
2. `MessageDecoder.decodesBatch`（⑥篇 6.2 预告的收口）：整段响应字节 → N 个 MessageExt；
3. **批记录拆包**：`INNER_BATCH_FLAG + NEED_UNWRAP_FLAG` 的消息再过一道 `decodeMessage` 拆成逻辑消息（⑥篇 6.5 "SimpleCQ 一条单元、客户端拆 N 条"的第二半）；
4. **tag 二次精筛**：broker 端过滤用的是 8 字节 tagCode（**hash，有误命中概率**），这里用订阅的 tag **字符串**再筛一遍（msgListFilterAgain）——经典的"hash 粗筛省 IO、字符串精筛保正确"双层；
5. FilterMessageHook 钩子 + msgId/offsetMsgId 规范化。

### 4.2 `ProcessQueue`：一队列一有序缓存

`TreeMap<queueOffset, MessageExt> msgTreeMap` + 读写锁 + `AtomicLong msgCount/msgSize`（L46-48，②闸门 5/6 读的就是它们）：

- `putMessage`：`msgTreeMap.put` 返回 old≠null 则**不计数**——同 offset 的重复解码直接覆盖（客户端唯一形态的"按 offset 去重"）；
- `getMaxSpan` = lastKey−firstKey（②闸门 7 的数据源）；
- 双时间戳 `lastPullTimestamp / lastConsumeTimestamp`：前者喂 ①1.3 的 pullExpired 自愈；
- **`cleanExpiredMsg` 是内存队列的 TTL**（L75+）：只对并发消费跑；判据是**消息带上了 CONSUME_START_TIME**（已投消费但没跑完）且超 `consumeTimeout` 分钟；每轮最多清 16 条、从 firstKey 端清；清出去的走 sendBack。它存在的根因恰是 4.3 的**无界队列**——任务排不到，消息就会在内存里老死。

### 4.3 消费执行体：池子的三个"名不副实"

`AbstractConsumeMessageService` L37-42（5.x 新抽象，Concurrently/Orderly 共用）：

```java
new ThreadPoolExecutor(consumeThreadMin, consumeThreadMax, 60s, new LinkedBlockingQueue<>() /* 无界! */)
```

- 默认 `consumeThreadMin = consumeThreadMax = 20`（Consumer L162/169）——配上无界队列，**`consumeThreadMax` 永远不会触达**（ThreadPoolExecutor 的队列满才扩容铁律）；想扩线程调 min；另外 5.x 的伸缩靠 `MQClientInstance.adjustThreadPool`（1min 巡检，①任务表）改 corePoolSize，以及**注入外部 executor**（`getConsumeExecutor`，ownsConsumeExecutor 标志）——三种弹性都不靠 max。
- `submitConsumeRequest`：按 `consumeMessageBatchMaxSize` 切批，而**默认 = 1**（L232）——listener 签名里的 `List<MessageExt>` 默认**每次只装一条**。误区二击破：那个 List 不是"RocketMQ 帮你批量了"，想真批量自己调这个参数（且批内 ack 是前缀语义，见 4.4）。
- 拒绝异常 → `submitConsumeRequestLater` 延迟重投，不丢。
- `ConsumeRequest.run`（L338+）开跑前两件门卫活：**pq 已 dropped → 整包直接放弃**（rebalance 收回的队列不再消费，等下次 rebalance 别人拉）；逐条 `pq.containsMessage` 复核（被 cleanExpired 摘掉的剔除）。listener 抛异常 = 视同 `RECONSUME_LATER`（L404）——**你的 listener 只要不 catch，重试就永远兜得住；catch 了却返回 SUCCESS 才是真丢消息**。

### 4.4 `processConsumeResult`：前缀 ack 与重试闭环

核心变量是 `context.getAckIndex()`（ConsumeConcurrentlyContext 默认 MAX，等价"全成功"）：

| status | ackIndex | 后果 |
|---|---|---|
| CONSUME_SUCCESS | 尾部（可被批量 listener `setAckIndex(k)` 前移） | `[ackIndex+1 .. end]` 走 sendBack |
| RECONSUME_LATER | **强制 -1** | 全量 sendBack |

- sendBack 前置 `containsMessage` 复核（L243-248）：已被 TTL 清掉的**不再 sendBack**（防重复入重试）。
- `sendMessageBack` → `CONSUMER_SEND_MSG_BACK` RPC → **链路篇 §1.4 拆过的 `consumerSendMsgBack`**（换 topic 为 `%RETRY%group`、延迟等级由 broker 按 reconsumeTimes 定）——②埋的"重试从哪进"在这闭合。RPC 失败兜底：`reconsumeTimes+1` 后本地重投（L252-258）。
- **BROADCASTING 分支只打 warn 不重试**（L231-237）——广播没有重试 topic，失败即事实。
- **ack 推进的精确语义**（L266-270 + removeMessage 体）：

```java
long offset = pq.removeMessage(msgs);   // 有剩余 → firstKey；空 → queueOffsetMax+1
if (offset >= 0 && !pq.isDropped())
    offsetStore.updateOffset(mq, offset, true /*increaseOnly*/);   // 只动内存表
```

提交的不是"这批的 max+1"，而是**"树上最老未 ack 条目的 offset"**——一条卡住，位点整体停在它身上（at-least-once 的头线阻塞），其余先 ack 只是从内存树上摘除。误区三击破：**并发消费"乱序完成"不会乱序提交，位点永远只承认连续前缀的下一格**。注意 `pullRequest.nextOffset` 早在 ② 的 FOUND 分支按 broker 的 nextBeginOffset 推进了——**拉取位点和提交位点从这一刻起就是两条独立轨道**，③ 的双账（pullOffset/commitOffset）在客户端这边同样成对。

### 验收问题（读完 ④ 必须能答）

- [ ] broker 已按 tagCode 过滤，客户端为何还要字符串二次筛？漏筛/过筛各由谁兜底？
- [ ] `consumeThreadMax=64` 却线程数纹丝不动，结合池子构造解释至少两种可能。
- [ ] listener 一次拿到 32 条的 List，需要什么前提？拿到后失败 2 条，怎么只重试这 2 条？（ackIndex 前缀协议）
- [ ] cleanExpiredMsg 判据为什么要求 CONSUME_START_TIME 存在，而不是"进 pq 即计龄"？
- [ ] 一条消息失败 sendBack 成功，但它还留在别的批次引用里吗？removeMessage 与 msgTreeMap 的一致性由什么保护？
- [ ] 拉取位点（nextOffset）与提交位点（ack 前缀）分别由哪两个事件推进？为什么必须解耦？

---

## ⑤ 位点提交闭环与 orderly 对照

### 5.1 一本 offsetTable，三条进路

客户端账本 `offsetTable: mq → ControllableOffset`（5.x 的 CAS+冻结对象）：

- **进路①：pull 搭车**（②组头 L453-460 → ③handler L798-803）——条件 `brokerAllowSuspend && hasCommitOffsetFlag`，值读 `READ_FROM_MEMORY`；
- **进路②：5s `persistAll`**（①任务表 → `RemoteBrokerOffsetStore.persistAll` L115-155）——只提交**还在手**的队列；不在了的直接 `offsetTable.remove`（L145-152）：rebalance 之后旧队列的脏账被顺手回收，不给新主添乱；
- **进路③：shutdown 最后一击**（`DefaultMQPushConsumerImpl.shutdown`）：先 `consumeMessageService.shutdown(awaitMillis)` 等在途批跑完 → `persistConsumerOffset()` → 再注销——**优雅下线自己补交一次，不等那个 5s 节拍**。

RPC 本性两条（L198-222）：**默认 ONEWAY**（丢了不追，下轮补发——位点是"流"不是"事务"）；**永远只写 master**（`findBrokerAddressInSubscribe(MASTER_ID)`，从库不许收账）。

**`ControllableOffset` 的冻结语义**（L59-81 + 类注释 L24-31）：`updateAndFreeze` 后常规 update 一律被拒。②的 OFFSET_ILLEGAL 四连先 freeze 再扔队列——防的是"dying pq 的在途 ack / 下一轮 5s persist 把旧 offset 倒灌进新分派"。解冻方式很优雅：**`removeProcessQueue` 连带 `removeOffset` 删整条，新队列拿到全新 unfrozen 对象**——又一次"坏状态整个扔掉重来"（§4.5 重连、③钳位、同一家族）。

`increaseOnly` 的用法分野也在此钉死：**并发传 true**（乱序完成、单调 CAS 防倒灌）；**orderly 传 false**（L302-304——同队列单批在途无竞态，且要允许 ROLLBACK 类倒退）。

### 5.2 端到端位点账的"最坏情况"

两段节拍串联：客户端 5s persist（oneway）+ broker 5s 落盘（⑥篇 6.4）→ **双进程接连崩溃，位点最多回退 ~10s 的已消费量**——这是 at-least-once 里"重复度预算"的出处；重复消费的根治手段只能是业务幂等（`UNIQ_KEY` + ⑥篇 §5.4 的 index 查询正是"按 key 查重放工具"的实现底座）。

### 5.3 orderly 对照速查（五维）

| 维度 | 并发（本篇主线） | 顺序 |
|---|---|---|
| 失败语义 | sendBack → `%RETRY%`，乱序换存活 | **SUSPEND_CURRENT_QUEUE_A_MOMENT：整队列本地挂起重投**（不进 RETRY；顺序 > 活性）；超 max 才 force-commit 跳头（L257-265）——而 orderly 默认 `maxReconsumeTimes = Integer.MAX_VALUE`（L314-318），**不设上限=失败队头永久阻塞**，这是它的运维代价 |
| 提交语义 | ack 连续前缀（firstKey） | **双树 + 显式 COMMIT**：`processMsgTreeMap` 记在途，`pq.commit()` 才产生 commitOffset；autoCommit 下 SUCCESS 不算数，手动模式自成 COMMIT/ROLLBACK 事务小协议（L245-297） |
| 并发度 | 1 队列 N 批在途 + maxSpan 闸门 | **1 队列 1 ConsumeRequest 在途**，跑完 10ms 续投/挂起档续投（L195-197），不需要 span |
| 队列所有权 | 无（rebalance 结果即分派） | `lockBatchMQ`（broker RebalanceLockManager）+ 心跳续约；②闸门 `isLocked` 检查 + 首拉 offset 校正（L304-327） |
| 过期治理 | cleanExpiredMsg（并发专属，L75-77 直接 return） | 无 TTL——队列卡住是设计语义，等人处理 |

## 全链路 recap（从 start 到第一条消息的 listener 回调，一次走完）

```
start 九步(①) ── registerConsumer ──► MQClientInstance（心跳/路由/5s位点 任务族 + pull/rebalance 两线程）
首次 rebalance(①): route→allocate→computePullFromWhere(初见策略!)→PullRequest 种子→总线②
循环体：
  [七道闸门②] ──pull(V2, sysFlag=commit|suspend|subVer)──► PullMessageProcessor③
      store.getMessage: CQ定位→clamp三元组→commitlog取字节（⑥篇 §6.4 的读路径）
      FOUND ──FileRegion 零拷贝/堆双路③──► 回调(netty线程)
        processPullResult④(解码+拆批+tag精筛) → pq.putMessage → ConsumeRequest(池20) → listener
        ack 前缀 → offsetTable → ③拉账/pull搭车/5s oneway → broker 双账 → 5s flush consumerOffset.json⑥
      空回 ──► Hold 表挂 15s ◄── Reput 派发完 arriving() 唤醒（③，唤醒重入不再挂）
      OFFSET_ILLEGAL ──► 钳位 → freeze+扔队列+rebalance 重建（②③⑤ 三方合流的自愈）
失败重试：sendBack → CONSUMER_SEND_MSG_BACK → %RETRY% topic →（下次 rebalance 给回来）④↔链路篇 §1.4 闭环
```

三条贯穿性收口：①**主语永远是 PullRequest**，push 是 15s 挂起 + 派发唤醒合谋的视效；②**位点是三轨流水**（拉轨 nextOffset / 账轨 ack 前缀 / 盘轨两段 5s），谁都不阻塞谁；③**一切故障处理同构**——扔掉局部状态（dropped/freeze/closeMaster/rebuild），靠周期性幂等重算收敛，没有原地修补协议。

---

## ⑥ POP 全链路：一条 CK 的生死（PopBufferMergeService / invisibleTime / Revive）

> 范围：经典客户端内源 pop（②.1 消息总线的另一分派）。broker/pop 下的 `PopConsumerService`（RocksDB KV 态、Lite 顺序 pop）未钻，仅点名。
> 与 push 的本质分野：**位点提交被"不可见时间 + 服务端对账"替代**——ack 从"推进 offset"变成"销账凭证"。

### 6.1 客户端：一台总线的另一条分派

- 前提·拨开关：assignment 里的 `mode=POP` 不是默认值，来自朝 broker 的 `SET_MESSAGE_REQUEST_MODE`（MQClientAPIImpl L3300）——两条正路：**编程式** `DefaultMQAdminExt.setMessageRequestMode(brokerAddr, topic, group, POP, popShareQueueNum, timeout)`（官方示例 `PopConsumer.java` 就是启动时自己拨，L62；LMQ 版 LMQPushPopConsumer L98 同款）或**运维式** `mqadmin setConsumeMode`（SubCommand L102）。broker 端存进 `MessageRequestModeManager extends ConfigManager`（JSON 持久化，QueryAssignmentProcessor L85/L107 接收）——**粒度 = per (topic, group)**，可灰度（同 topic 的 A 组 pop、B 组 push 互不干扰）；且 **per-broker 存储，主备要各拨一遍**（测试 PopSlaveActingMasterIT L502/505 即此坑）；`popShareQueueNum` 是 pop 下逻辑队列共享的物理队列数提示。
- 客户端侧唯一动作：`setClientRebalance(false)`（PopConsumer L50）——rebalance 从本地改为问 broker（QUERY_ASSIGNMENT），业务 listener 零改动。
- 种子：分配表带 `mode=POP` 的队列（`RebalanceImpl.updateMessageQueueAssignment` L508+ → `new PopRequest` L677）→ ②.1 总线 `getMessageRequestMode()==POP` 分派 `popMessage`。
- POP RPC（`MQClientAPIImpl.popMessage` L844）：带 `invisibleTime`、`pollTime`（broker 侧长轮询）、`initMode`（首拉位点策略）；响应每消息附 **extraInfo** 串（`ExtraInfoUtil.split` 解出 reviveQid/queueId/ckQueueOffset/popTime/invisibleTime…）——**ack 的凭证从 broker 来、原样还给 broker**，客户端只保管不理解。
- 消费完（`ConsumeMessagePopConcurrentlyService`）：
  - 成功 → `DefaultMQPushConsumerImpl.ackMessageAsync`（L287）/**batchAck**（L311）——`MQClientAPIImpl` L919-938 按 `retry@queueId@ckOffset@popTime` 做 mergeKey，**把同一 CK 的多条 ack 合成一个 BATCH_ACK**；
  - 失败 → `changeInvisibleTimeAsync`（L337）把 nextVisible 改近=快速重投；或**干脆不 ack**，让 invisible 到期走服务端兜底。
- 与 push 对照：**没有 sendBack 了**——重试的主动权整个交给服务端。

### 6.2 broker 弹出：一批一 CK 一钉

`PopMessageProcessor.processRequest`（L220）：

1. `compensateBasicConsumerInfo(CONSUME_POP, CLUSTERING)`（L240，注释明示 **pop 只支持集群模式**）；`isTimeoutTooMuch` 拒陈旧请求（L244）。
2. `popMsgFromTopic`（L667）：`getPopOffset`（含 reset 检查）→ **并发闸**"Too much msgs unacked"（未销账超配额停弹，L310 区）→ 复用量链读门面 `messageStore.getMessageAsync`——**pop 不是新读引擎，是账本引擎**。
3. **一批一 CK**（`appendCheckPoint` L952-980）：`PopCheckPoint{num, startOffset, diffs[], bitMap, popTime, invisibleTime, reviveQid, ...}`——弹 N 条只立 **1 条账**；先塞 `popBufferMergeService.addCk`（L490）内存，buffer 拒收才走落盘路径。
4. CK 落盘 = `buildCkMsg`（L935）：checkpoint JSON 化成一条普通消息写进 **revive topic**（queueId 由 `ckMessageNumber` 轮转分散，L496），**`deliverTimeMs = reviveTime - ackTimeInterval`——invisible 到期检查交给 timer wheel 定时投递**，不需要任何扫描线程找过期项。

### 6.3 `PopBufferMergeService`：多层削减，让正常路径零 revive 写

`commitOffsets: "topic@cid@queueId" → Queue<PopCheckPointWrapper>`（L51），扫描循环（L240-300）：

| 情形 | 处置 | 意义 |
|---|---|---|
| ack 已齐（bitMap 覆盖，`isCkDone`） | 出队即弃，**CK 永不落盘** | 快路径 pop→ack 全程内存 |
| 逼近 `popCkStayBufferTimeOut`（快到期）/ 滞留超 `popCkStayBufferTime` | `putCkToStore` 落盘（可 async append） | 慢路径，交给 revive |
| 已落盘 CK 等齐 ack 且 `enablePopBatchAck` | ack 合并成 batch 写 | 慢路径再省 |
| justOffset（仅推位点占位） | 不在库必须补写 | offset 不丢账 |

`commitOffset`（L387-412）：buffer 消化即推进 `consumerOffsetManager.commitOffset`——**pop 的 offset 由 broker 凭销账推进，不走客户端 5s persist**：push"客户端报进度"，pop"服务端记账等销账"。

### 6.4 `PopReviveService`：对账与重投（一 revive 队列一线程，仅 master）

拉取 revive topic 当内部消费者（`getReviveMessage` L208/354），两类记录配对：

- **CK 到点（无人配对的 ack）→ `reviveMsgFromCk`（L553）**：按 bitMap 跳过已销、`ackOffsetByIndex(j)` 还原各 msgOffset → `getBizMessage` 回读消息体（**已物理清理则静默跳过**）→ `reviveRetry`：**重投 `buildPopRetryTopic` 的 pop 重试队**（retry 队本身则回自己——"续命即重试"，delay 走 retry 阶梯）；
- **重投失败 → `rePutCK`（L602）**：新 CK 回写 revive，退避阶梯 `ckRewriteIntervalsInSeconds`，到顶按 `skipWhenCKRePutReachMaxTimes` 弃账——**连 CK 自己都有重试策略**；
- 处理完**按序** commit revive offset（`inflightReviveRequestMap` 前序不毕不后移，L585-597）——revive 是严格有序的状态日志，乱序会让 offset 跳号漏账。

### 6.5 push vs pop 结算表

| 维度 | push（①-⑤） | pop（本节） |
|---|---|---|
| 重试驱动 | 客户端 sendBack → `%RETRY%`（④.4） | 服务端：不 ack → 到期 revive → pop 重试队；`changeInvisibleTime` 可加速 |
| 位点主权 | 客户端 ack 前缀 → 上报 → broker 记账（③双账/⑤三进路） | **broker 凭销账自推**（6.3 尾），客户端只持 extraInfo |
| 重复窗口 | 两段 5s（≈10s） | **invisibleTime 本身**，业务可调 |
| 在途状态 | ProcessQueue 内存树（④.2），客户端死账不动 | 服务端 CK 账，实例死亡不影响对账 |
| 并发弹性 | 队列数封顶消费者数（rebalance 分派） | **队列不再是独占单位**——组内任意实例可抢任意消息，LMQ/多租户友好 |
| 写放大削减 | （无此问题） | 批 CK / 内存 buffer 免写 / batch ack 合并 / timer 代扫描，层层设卡 |

### 验收问题（读完 ⑥ 必须能答）

- [ ] invisible 到期为什么用"定时消息回投"而不是扫描？6.3 的 buffer 与 timer 的 reviveTime 在时间上怎么衔接（`- ackTimeInterval` 那个提前量是干嘛的）？
- [ ] 一批 32 条的 pop，最顺与最惨各产生几次 revive 写？逐层指出触发点。
- [ ] pop 的 offset 由谁在什么事件推进？为什么 pop 重复窗口=invisibleTime 而 push 是两段 5s？
- [ ] 为什么 revive 必须按序消费、`inflightReviveRequestMap` 在防什么？
- [ ] `changeInvisibleTime`（主动快重试）与"不 ack 等兜底"（被动）各适合什么业务画像？
- [ ] batchAck 的 mergeKey 为什么要含 popTime？（同队列先后多批 CK 的区分）
- [ ] 为什么说"pop 不是新读引擎，是账本引擎"？它的复用点在哪个函数？
