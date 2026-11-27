# RocketMQ 挂起请求唤醒链路：长轮询的"挂起-唤醒"全解剖（5.5.1）

> 问题：消费者起来后向 broker 拉消息，topic 此时无消息，请求在 broker 被挂起（一定时间内不写回响应）；生产者随后发送消息，被挂起的请求是怎么被处理的？
> 口径：行号对照本机 `~/github/rocketmq`（5.5.1-38）逐行核验；本篇是《消费链路精读》§③3.4 的放大镜版，发送侧背景见《发送链路精读》§4.3、读路径见其 §6.5，交叉引用处以 §前缀标明。
> 一句话答案：**被挂起的请求不是"被通知后取消息回包"，而是被存储层的派发事件（ConsumeQueue 索引就绪后）触发，在拉取线程池里把原始请求原样重跑一遍拉取流程（且不许再挂起），用查到的新消息组装响应写回原 channel**；事件驱动为主、5s 扫描兜底，两条路汇合在同一个 `notifyMessageArriving` 裁决函数上。

---

## ① 前提：请求是怎么被"挂起"的

消费者 pull 到 broker，`getMessage` 查 ConsumeQueue 发现没有新消息，返回 `PULL_NOT_FOUND`。`DefaultPullMessageResultHandler`（L172-187）：

- 条件 `brokerAllowSuspend && hasSuspendFlag`（客户端 sysFlag 带了 suspend 位）成立时，**不写回响应**，而是把原始 `RemotingCommand` + Netty `Channel` + 挂起时长（客户端传的 `suspendTimeoutMillis`，长轮询上限 `BROKER_SUSPEND_MAX_TIME_MILLIS=15s`）+ 订阅过滤器打包成 broker 侧的 `PullRequest`，调 `suspendPullRequest(topic, queueId, pr)`；
- `PullRequestHoldService.suspendPullRequest`（L45-58）把它挂进 `pullRequestTable: ConcurrentMap<"topic@queueId", ManyPullRequest>`，并给原 request 打 `setSuspended(true)`。

**关键认知：挂起期间没有任何线程在为这个请求 sleep/wait**。"等待"的实体就是这张 ConcurrentHashMap 里的一个对象，Netty channel 被晾着不回包，处理线程早已释放去服务别的请求。客户端侧对应的 RPC 超时是 30s（`CONSUMER_TIMEOUT_MILLIS_WHEN_SUSPEND`，消费篇 §2.3）——30 > 15 留足网络余量，挂起发生在 broker，不发生在客户端。

---

## ② 生产者发消息后，唤醒链路五步

### 2.1 第 1 步：消息写入 CommitLog（此时还没人唤醒）

生产者 `send` → broker `asyncPutMessage` → 消息 append 进 CommitLog（刷盘/复制后返回发送成功，发送篇 §4）。**注意此时 ConsumeQueue 索引还没建**——如果这时就唤醒挂起的 pull，复查照样查不到消息，等于空转。所以唤醒不在写入路径上。

### 2.2 第 2 步：ReputMessageService 异步派发，先建索引再通知

`DefaultMessageStore.ReputMessageService`（L2655 起）是 store 里一个独立线程，`run()` 循环 **每 sleep 1ms 跑一次 `doReput()`**（L2823-2826），追着 CommitLog 的新数据做派发。对每条新消息（doReput L2742-2749）：

```java
DefaultMessageStore.this.doDispatch(dispatchRequest);   // ① 先派发：BuildConsumeQueue 写 CQ 索引、BuildIndex 写索引文件
if (isNotifyMessageArriveWhenReput()) {
    notifyMessageArriveIfNecessary(dispatchRequest);    // ② 后通知
}
```

顺序是正确性关键：**先 `doDispatch` 把 20 字节的 ConsumeQueue 条目写好（消费者可读的索引就绪），再发通知**——拉取取数走 CQ 索引，"派发完成才通知"把长轮询的语义精确到"可消费的索引已就绪"。发送篇 §4.3"写不等索引、读等索引"的母题在此合龙。

### 2.3 第 3 步：arriving 事件扇出到 HoldService

`notifyMessageArriveIfNecessary`（DefaultMessageStore L2644-2652）在 `longPollingEnable` 开启时回调：

```java
messageArrivingListener.arriving(topic, queueId,
    dispatchRequest.getConsumeQueueOffset() + 1,   // 注意：传的是 cqOffset+1，即"下一条可拉位点"
    tagsCode, storeTimestamp, bitMap, propertiesMap);
```

listener 的实现是 `NotifyMessageArrivingListener`（BrokerController 装配时构造、注入 store），`arriving`（L41-54）一次扇出四路：`pullRequestHoldService`（经典 pull 长轮询，本篇主角）、`popMessageProcessor`、`notificationProcessor`、`liteEventDispatcher`（LMQ lite topic 走独立分支提前 return）。另有 `notifyMessageArrive4MultiQueue`（L2791-2817）为 `PROPERTY_INNER_MULTI_DISPATCH` 的多队列消息（LMQ）逐队列补发 arriving。

### 2.4 第 4 步：HoldService 匹配挂起的请求

`PullRequestHoldService.notifyMessageArriving`（L123-184），按 `"topic@queueId"` 定位到 `ManyPullRequest`，`cloneListAndClear()` 摘出全部挂起请求逐个裁决，**三种去向**：

1. **位点够到了 + 过滤匹配 → 立即唤醒**（L143-161）：`newestOffset > request.getPullFromThisOffset()`（新消息位点 > 该请求要拉的位点），再用请求自带的 `MessageFilter` 做匹配——先 `isMatchedByConsumeQueue(tagsCode, cqExtUnit)`（tag hash 粗筛），bitmap 命中且 properties 非空时再 `isMatchedByCommitLog` 按属性精筛（SQL92）。匹配则调 `executeRequestWhenWakeup(channel, request)`。
2. **已超挂起时限 → 到点唤醒**（L164-174）：`now >= suspendTimestamp + timeoutMillis`，同样走 `executeRequestWhenWakeup`——哪怕还是没消息，也回一个 `PULL_NOT_FOUND` 了断。
3. **都不满足 → 放回表里继续挂**（L176-181）：`replayList` → `mpr.addPullRequest(replayList)`。典型场景：新消息到了但 **tag 不匹配**这个消费者的订阅——不该为一条它不关心的消息白白唤醒它。

两个细节：

- 若事件带来的 `maxOffset <= pullFromThisOffset`，会现场再查一次 `getMaxOffsetInQueue`（L134-141）——事件可能是"旧闻"（派发有延迟/多条消息竞态），以 store 当前真实水位为准，防漏唤醒。
- 摘出-裁决-放回不是原子的，靠 `ManyPullRequest` 内部 synchronized + 位点复查兜住并发窗口。

### 2.5 第 5 步：executeRequestWhenWakeup——重跑一遍拉取流程，绝不再挂

`PullMessageProcessor.executeRequestWhenWakeup`（L806-834）：

```java
final RemotingCommand response = processRequest(channel, request, false, ...);  // 第三参 brokerAllowSuspend = false
if (response != null) {
    response.setOpaque(request.getOpaque());   // 复用原请求的 opaque，客户端才能对上号
    response.markResponseType();
    NettyRemotingAbstract.writeResponse(channel, request, response, ...);
}
```

三个要点：

- **不是"把新消息直接塞给消费者"，而是拿原始请求把 `processRequest` 完整重跑一遍**：重新走 `getMessage` 查 CQ → 读 CommitLog → 组装响应。这次 CQ 索引已就绪，返回 `SUCCESS` + 消息体（默认 `transferMsgByHeap=true` 堆拷贝，关掉则 `ManyMessageTransfer`/FileRegion 零拷贝，消费篇 §3.3）。
- **`brokerAllowSuspend=false` 强制不许再挂起**：即使复查竟还是空（比如消息被 tag 精筛掉），也直接回 `PULL_NOT_FOUND`，一次了断。否则一个请求可能无限挂-醒-挂循环。
- **执行线程切换**：包成 `RequestTask` 投进 `pullMessageExecutor` 线程池（L833），而不是在 ReputMessageService 派发线程里直接跑——**派发线程是全 broker 消息可见性的命脉，不能被一次拉取的磁盘 IO 卡住**。

顺带：`brokerAllowSuspend=false` 还影响记账——`tryCommitOffset`（L792-804）里 `storeOffsetEnable = brokerAllowSuspend && hasCommitOffsetFlag`，唤醒重入这一发**不消费 pull 头搭车的 commitOffset**（避免同一请求的位点被重复处理），只记 transient 的 pullOffset（消费篇 §3.2 两本账）。

---

## ③ 客户端侧的闭环

响应沿原 channel 写回（opaque 不变），客户端那个 30s 超时的异步 RPC 正常完成 → `PullCallback.onSuccess` → FOUND → `processPullResult` 解码精筛 → 消息进 ProcessQueue → 投消费线程池回调 listener → **同时立即续装下一发 pull**（`pullInterval=0`，消费篇 §2.5）。下一发若又没消息，再挂 15s——如此循环，就是"push 的手感"。

若唤醒回的是空包 `PULL_NOT_FOUND`（到点了断/tag 不匹配后超时），客户端 NO_NEW_MSG 分支同样**立即续装**——长轮询的客户端半边就是"回来即再发"，因为 broker 已把这一发挂了 15s。

---

## ④ 兜底：事件丢了怎么办

唤醒主路是事件驱动，但 HoldService 自己也是个线程：`run()`（L68-91）每 **5s**（`waitForRunning(5 * 1000)`）醒一次跑 `checkHoldRequest()`（L101-117）——遍历表里每个 `topic@queueId`，主动查 `getMaxOffsetInQueue` 再走一遍同一个 `notifyMessageArriving`。所以即使 arriving 事件因竞态漏掉，最坏 **15s + 5s** 内也必回响应，客户端永远等得到。

- 若配置关了 `longPollingEnable`，扫描间隔缩为 `shortPollingTimeMills=1s`（L73-77），退化成 1s 一轮的短轮询；同时 store 侧 `notifyMessageArriveIfNecessary` 的门（L2645）也关掉，事件主路整体熄火，只剩扫描。
- 另有一个特殊唤醒口 `notifyMasterOnline()`（L186-202）：slave 升主时无条件唤醒表里全部挂起请求——挂起是 master 专属语义（消费篇 §3.1：slave 转发 pull 前清 suspend flag），角色切换后旧账必须一次清光。

---

## ⑤ 一图收束

```
consumer pull ──► getMessage: NO_NEW_MSG
                    └─► suspendPullRequest: 挂进 pullRequestTable（无线程等待，channel 晾着）
producer send ──► CommitLog append（此刻不唤醒）
ReputMessageService(1ms 循环) ──► doDispatch: 写 ConsumeQueue 索引  ← 先
                              └─► arriving(topic, qid, cqOffset+1) ← 后
NotifyMessageArrivingListener ──► HoldService.notifyMessageArriving
    位点够到 & tag/SQL92 匹配 ──► executeRequestWhenWakeup
    超 15s 时限              ──► executeRequestWhenWakeup（空手了断）
    不匹配/未到点            ──► replayList 放回继续挂
executeRequestWhenWakeup ──► pullMessageExecutor 线程池重跑 processRequest(allowSuspend=false)
                         ──► SUCCESS+消息 / PULL_NOT_FOUND，opaque 原样写回
consumer onSuccess ──► listener 消费 + 立即续装下一发 pull
兜底：HoldService 5s 周期扫描，事件丢失最坏 15+5s 必回
```

三条贯穿性收口：

1. **等待的实体是数据结构不是线程**——挂起 = 表里一个对象 + 晾着的 channel，broker 用一张 ConcurrentHashMap 扛住成千上万个"在等消息"的请求，零线程成本。
2. **唤醒 = 重放而非投递**——事件只负责"催"，消息本体永远由重跑的 `processRequest` 从存储自取；通知里不带消息，只带位点和过滤要素，天然避免"通知与取数不一致"。
3. **事件为主、周期兜底、到点必断**——三种裁决共用一个函数，任何请求的挂起时长有硬上界（15s+5s），不存在"永远挂着"的请求。

## 涉及源码文件

| 角色 | 文件 |
|---|---|
| 挂起入口 | `broker/.../processor/DefaultPullMessageResultHandler.java`（L172-187） |
| 挂起表 + 裁决 + 兜底扫描 | `broker/.../longpolling/PullRequestHoldService.java` |
| 事件扇出 | `broker/.../longpolling/NotifyMessageArrivingListener.java` |
| 派发线程 + 通知门 | `store/.../DefaultMessageStore.java`（ReputMessageService L2655、notifyMessageArriveIfNecessary L2644） |
| 唤醒重入 | `broker/.../processor/PullMessageProcessor.java`（executeRequestWhenWakeup L806、tryCommitOffset L792） |