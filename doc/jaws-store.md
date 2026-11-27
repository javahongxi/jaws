# jaws-store：RocketMQ 风格存储引擎的设计与协议验证

jaws-store 是 jaws 仓库内的存储引擎实践模块（约 3.7k 行 main + 1.3k 行测试），复刻 RocketMQ 存储内核的核心机制：mmap CommitLog 顺序写、ConsumeQueue 定长索引、异步/组提交刷盘、长轮询挂起唤醒。它的定位不是 MQ 产品线，而是 **jaws 协议的高吞吐实战验证件**——类比 harbor 之于 wire 协议的 Nacos 兼容性验证，store 用真实 MQ 负载去压 jaws benchmark 压不到的路径。

五篇前置精读（[发送链路](mq/rocketmq-发送链路精读.md)、[消费链路](mq/rocketmq-消费链路精读.md)、[刷盘与复制](mq/rocketmq-刷盘与复制精读.md)、[挂起请求唤醒链路](mq/rocketmq-挂起请求唤醒链路.md)、[高性能高吞吐成因分析](mq/rocketmq-高性能高吞吐成因分析.md)）是它的设计文档；本文反过来讲实现落了什么、简化了什么、验证出了什么。

## 1. 定位与边界

| 维度 | 做 | 不做（声明式简化） |
|---|---|---|
| 存储内核 | mmap CommitLog + ConsumeQueue + 异步 reput 建索引（失败持位重试、越界熔断写，§4.2）+ 过期文件回收 / 磁盘水位（§6.1） | IndexFile（按 key/tag 旁路检索）、多路径存储 |
| 持久性 | 异步刷盘节拍器 + SYNC_FLUSH 组提交 | HA 主从复制、Dledger |
| 消费模型 | 长轮询拉取、批量拉取、**broker 端位点权威 + 提交链路**（§7.3），客户端不留副本；订阅类型取 Pulsar 四式中的 **Exclusive**（§7.1） | Pop 消费、rebalance、Shared / Failover / Key_Shared 三式、位点过期清理、按 earliest/时间戳重置位点 |
| 消息类型 | 普通消息 | 事务消息、延迟消息、顺序消息（及其所依赖的锁机制） |
| 接入 | jaws 协议直连或注册中心发现（多 master 分片，§7.1） | NameServer 专用路由、topic 路由下发 |
| 可观测性 | 日志，加上一个 `MessageStore.getPullCount()` 测试探针（证明请求真的到了 broker） | 运维查询契约、stats 快照、逐条延迟直方图、外部监控系统对接 |
| 客户端 | sync/async/oneway 发送、**显式批量发送**（一次 RPC 多记录、共享一次 fsync 屏障，§7.2）、拉取泵、inflight 流控 | autoBatch（自动攒批）、压缩、MQFaultStrategy |

依赖单向 `jaws-store → jaws-core`，core 不反向依赖；包结构沿用 harbor 风格——`org.hongxi.jaws.store` 根包直接放引擎，`org.hongxi.jaws.store.client` 放薄客户端。

## 2. 总体架构

一条消息的完整旅程：

```
producer ──putAsync──▶ CommitLog.putMessage（两层锁 + memcpy 进 page cache）
                          │
                          ├─ ASYNC_FLUSH：返回即完成，后台节拍器刷盘
                          └─ SYNC_FLUSH：submitFlush 进组提交批次，一次 fsync 满足整批
                          │
              ReputMessageService（单线程异步追赶 wrotePosition）
                          │  先建索引，后发通知（顺序不可颠倒）
                          ▼
              ConsumeQueue.putMessagePositionInfo（queueOffset × 20 定位写 20B 单元）
                          │
              PullRequestHoldService.notifyMessageArrive（唤醒挂起的 pull）
                          ▼
consumer ◀──pullAsync─── getMessage：CQ 数组式寻址 → CommitLog 切片解码
```

文件地图（`jaws-store/src/main/java/org/hongxi/jaws/store/`）：

| 文件 | 职责 |
|---|---|
| [MappedFile](../jaws-store/src/main/java/org/hongxi/jaws/store/MappedFile.java) / [MappedFileQueue](../jaws-store/src/main/java/org/hongxi/jaws/store/MappedFileQueue.java) | 单文件 mmap 与文件序列管理，创建即预热 |
| [CommitLog](../jaws-store/src/main/java/org/hongxi/jaws/store/CommitLog.java) / [MessageCodec](../jaws-store/src/main/java/org/hongxi/jaws/store/MessageCodec.java) | 全局物理日志、记录编解码、两层锁写入 |
| [ConsumeQueue](../jaws-store/src/main/java/org/hongxi/jaws/store/ConsumeQueue.java) / [ReputMessageService](../jaws-store/src/main/java/org/hongxi/jaws/store/ReputMessageService.java) | 逻辑索引与异步派发 |
| [AsyncFlushService](../jaws-store/src/main/java/org/hongxi/jaws/store/AsyncFlushService.java) / [GroupCommitService](../jaws-store/src/main/java/org/hongxi/jaws/store/GroupCommitService.java) | 两种刷盘策略，共享 [FlushService](../jaws-store/src/main/java/org/hongxi/jaws/store/FlushService.java) 基类 |
| [PullRequestHoldService](../jaws-store/src/main/java/org/hongxi/jaws/store/PullRequestHoldService.java) | 长轮询挂起/唤醒/兜底扫描 |
| [MessageStore](../jaws-store/src/main/java/org/hongxi/jaws/store/MessageStore.java) | 门面装配 + 重启恢复重放 |
| [StoreBroker](../jaws-store/src/main/java/org/hongxi/jaws/store/StoreBroker.java) / [StoreService](../jaws-store/src/main/java/org/hongxi/jaws/store/StoreService.java) / [BrokerInfo](../jaws-store/src/main/java/org/hongxi/jaws/store/BrokerInfo.java) | jaws 协议导出与 RPC 契约（全 CompletableFuture），BrokerInfo 上报 brokerName |
| [ConsumerOffsetStore](../jaws-store/src/main/java/org/hongxi/jaws/store/ConsumerOffsetStore.java) / [OffsetResult](../jaws-store/src/main/java/org/hongxi/jaws/store/OffsetResult.java) | broker 端位点表（RocketMQ `ConsumerOffsetManager` 角色）与位点 RPC 的 found/offset 结果（§7.3） |
| [client/](../jaws-store/src/main/java/org/hongxi/jaws/store/client/) | StoreProducer / StoreConsumer / MessageListener |

数据目录：`{storePath}/commitlog/` 放物理日志（文件名为 20 位零填充起始偏移，默认单文件 1GB），`{storePath}/consumequeue/{topic}/{queueId}/` 放索引（默认 300000 单元 × 20B = 6MB/文件），`{storePath}/config/consumerOffsets.properties` 放 broker 端位点快照（§7.3）。

## 3. 写路径精读

### 3.1 记录格式

[MessageCodec](../jaws-store/src/main/java/org/hongxi/jaws/store/MessageCodec.java) 定义 44 字节定长头 + body + 尾部 topic（大端）：

| 偏移 | 字段 | 类型 | 说明 |
|---|---|---|---|
| 0 | totalSize | int | 整条记录长度，恢复扫描的步进依据 |
| 4 | magic | int | `0x4A415753`（"JAWS"），合法性校验 |
| 8 | queueId | int | 逻辑队列号 |
| 12 | bodyCrc | int | body CRC32（读路径暂不复核） |
| 16 | queueOffset | long | 队列内逻辑位点 |
| 24 | physicalOffset | long | **全局锁内 patch**（编码时只有占位 0） |
| 32 | storeTimestamp | long | **全局锁内 patch** |
| 40 | bodyLen | int | body 长度 |
| 44 | body + topicLen(1B) + topic | bytes | topic 上限 255 字节 |

对比 RocketMQ 的 CommitLog 编码：去掉了 bornHost/storeHost、sysFlag、properties 等字段，保留"定长头 + 变长 body + 尾部 topic"的骨架——恢复扫描和 reput 派发依赖的正是 totalSize/magic/queueOffset 这几个字段。

### 3.2 两层锁：全局临界区只剩一次 memcpy

[CommitLog.putMessage](../jaws-store/src/main/java/org/hongxi/jaws/store/CommitLog.java) 把锁拆成两层：

1. **per topic-queue 锁**：分配 `queueOffset`（AtomicLong getAndIncrement）+ 编码进线程本地 direct buffer。CPU 工作按队列并行，不同队列互不阻塞。
2. **全局 putMessageLock**：只做三件事——patch physicalOffset/storeTimestamp、memcpy 进 mmap 切片、必要时滚动文件。

编码复用 `ThreadLocal<ByteBuffer>`（direct，容量 maxMessageSize），数据路径不经过 JVM 堆：direct buffer → page cache。这是 RocketMQ `encode` 在 putMessageLock 外完成、锁内只 `appendMessage` 的同款思路。

两个配套机制：

- **page cache 失速探针**：`beginTimeInLock` 记录全局锁持有起点，超过 1s 判定 `isOSPageCacheBusy`，新写入直接返回 `OS_PAGE_CACHE_BUSY` 快速失败——memcpy 临界区本应微秒级，持锁超秒说明内核卡在缺页或写回。
- **文件滚动与空洞**：旧文件"未满但剩余不足"时 `rollNextFile()` 强制创建后继文件，尾部留下未用空洞，因此**物理偏移跨文件不连续**。顺序读者（reput、恢复重放）用 `skipFileTail(offset)` 跳到下一文件边界继续。

### 3.3 mmap 预热：write-then-force

[MappedFile](../jaws-store/src/main/java/org/hongxi/jaws/store/MappedFile.java) 新建文件时逐页（4KB 步进）写零触发缺页，随后 `force(false)` 一次把零页写回——对齐 RocketMQ `warmMappedFile` 的 mlock/MAP_POPULATE 意图。两个成本都被挪出写入路径：

- 不预热：首波并发 append 集体触发缺页 + 块分配，写线程全卡在内核里（macOS/APFS 上稀疏映射尤其明显）；
- 预热不 force：零页全是脏页，**第一次真实 fsync 要背整文件的写回**，组提交首批直接超时。

首个 commitlog 文件的预热进一步提前到 [MessageStore.start()](../jaws-store/src/main/java/org/hongxi/jaws/store/MessageStore.java) 的 `preallocateFirstFile()`，在监听端口之前完成，不占首个请求的全局锁。

## 4. 读路径与长轮询

### 4.1 ConsumeQueue：数组式寻址

[ConsumeQueue](../jaws-store/src/main/java/org/hongxi/jaws/store/ConsumeQueue.java) 每单元定长 20B：`commitLogOffset(8) + size(4) + tagsCode(8, 保留 0)`。定位 queueOffset N 就是 `N × 20` 的数组寻址，无解析无查找。写入复用单 reput 线程持有的 `unitBuffer`，无 per-message 分配。

CQ 是**派生物**：独立 1s 刷盘节拍、不参与组提交、无任何持久性承诺——所有持久性成本由 CommitLog 独家支付，重启时 CQ 删掉重建（见 §6）。

### 4.2 先建索引，后发通知，落空则持位重试、越界则熔断写入

[MessageStore.onDispatch](../jaws-store/src/main/java/org/hongxi/jaws/store/MessageStore.java) 严格两步：先 `putMessagePositionInfo` 落 CQ 单元，再 `notifyMessageArrive`。顺序颠倒会让被唤醒的 pull 查不到索引而空转——这是 RocketMQ doDispatch → notifyMessageArriveIfNecessary 顺序约束的直接复刻。

两步之外还有第三步：**落空就不推进**。`onDispatch` 上报该单元是否写成功，[ReputMessageService.runLoop](../jaws-store/src/main/java/org/hongxi/jaws/store/ReputMessageService.java) 收到 false 时停在原地、按 50ms 起指数退避（上限 1s）重试同一条记录。原因在 §4.1 的数组寻址：跳过一条等于在定长数组里留一个 20B 零单元，而队列尾部由写入位置推出、会**越过**这个空洞继续前进——pull 于是读到 `commitLogOffset=0 / size=0` 的槽位，`getMessage(0, 0)` 拿空 slice 去 decode 直接抛 BufferUnderflowException，该队列从空洞起再也读不下去，只能等重启全量重放修复。跳过还会骗过 §6.1 的保护位点：它的本意是"未派发的字节不删"，推进即宣称已派发。

代价是把活性让给完整性。dispatcher 单线程，一条持位期间其后所有 topic 的新消息都不建索引（写路径暂时不受牵连：仍 `PUT_OK`，queueOffset 由 per topic-queue 的写侧计数器给出，见 §3.2）；保护位点同时钉在原地，过期文件回收一并停住。故障若是暂时的，清除后自动续上，队列内顺序不变。

**对照 RocketMQ**（develop 分支本地源码核实）：它既不是纯跳过也不是无限持位，而是**有界重试 + 熔断**。文件版 CQ 在派发调用内部对同一条记录原地重试 30 次、每次 `Thread.sleep(1000)`（`ConsumeQueue.putMessagePositionInfoWrapper` L725）；`reputFromOffset += size` 排在 `doDispatch` 之后，所以重试期间位置天然是持住的（`DefaultMessageStore.ReputMessageService.doReput`）。30 次仍写不下就打 `[BUG]consume queue can not write` 并 `runningFlags.makeLogicsQueueError()`；该位一旦置起 `isWriteable()` 即为 false（`RunningFlags` L80），broker 侧 `HookUtils.beforePutMessage`（L76）从此对每次写入直接回 `SERVICE_NOT_AVAILABLE`，而 `isCQWriteable()` 又使后续记录的重试循环整体短路、位置被推过去——**新写停接、存量索引跳过**。RocksDB 版走另一条路：`putMessagePositionInfoWrapper` 抛 `RocksDBException`，`doReput` catch 后直接 return（位置不推进），外层 `run()` 的 `catch (Throwable)` + 1ms 节拍把它变成近似无限持位。

无限持位唯一的失控在写侧：索引不再前进，CommitLog 却还能无限长，保护位点钉住后回收也停住，最后撑满的是磁盘。所以把 RocketMQ 的另一半也取了——**越界熔断**。同一条记录重试到 `indexDispatchMaxRetries`（默认 30，取自 `ConsumeQueue.putMessagePositionInfoWrapper` 的重试次数）仍落不下，[ReputMessageService](../jaws-store/src/main/java/org/hongxi/jaws/store/ReputMessageService.java) 打一条 ERROR 并通过 `onIndexHealthChanged(false)` 通知 [MessageStore](../jaws-store/src/main/java/org/hongxi/jaws/store/MessageStore.java) 置起不可写位（`isWriteable()` 变 false）；此后 `putMessageAsync` / `putMessagesAsync` 在任何 append 之前直接回 `PutStatus.SERVICE_NOT_AVAILABLE`，与 broker 在 `HookUtils.beforePutMessage` 里判 `RunningFlags.isWriteable()` 同一形状。

只取它的"停接新写"，仍不取它的"跳过"：位点继续持住、重试继续做，第一条落下的记录触发 `onIndexHealthChanged(true)` 自动清位，写入重新放行。熔断后每 1s 一次的空转重试是全部额外开销，换来的是队列连续——被拒的写入不占 queueOffset（偏移在写侧才分配），自愈后不留零单元；这一点跳过做不到。与 RocketMQ 的差别只剩两处：它的 30 次重试是内联 `sleep(1000)`，这里由 reput 自己的退避驱动（50ms 起、上限 1s）；它置位后还会让后续记录短路跳过，这里的熔断只管拒写。

`stopped` 判在两处——内层循环每条记录前判一次，派发失败时也先判再决定要不要重试。关机中断落在建文件上属于预期退出（CQ 是派生物，重启重建），不该触发重试，更不该报成故障。测试见 §10 的 `heldIndexDispatchIsRetriedAndLeavesNoHole` 与 `exhaustedIndexRetriesFuseWritesUntilTheyHeal`。

### 4.3 挂起/唤醒的竞态防护

[PullRequestHoldService](../jaws-store/src/main/java/org/hongxi/jaws/store/PullRequestHoldService.java) 三道防线：

1. **HoldRequest.completed（AtomicBoolean CAS）**：唤醒与超时扫描两条路径只允许一条 complete future；
2. **挂起后 re-check**：[MessageStore.getMessageAsync](../jaws-store/src/main/java/org/hongxi/jaws/store/MessageStore.java) 注册挂起后立即重查 maxOffset——空读与挂起之间到达的消息，其通知已被错过，re-check 关闭这个窗口；
3. **周期扫描兜底**：deadline 到期或 maxOffset 前进都会触发 wakeup，通知丢失不致命。

延迟趋近 push，成本趋近 pull：broker 不维护任何 per-consumer 推送状态——§7.3 的位点表记的是组级进度，不是推送游标，唤醒路径与它互不相干。

### 4.4 挂起时长与三级超时预算

数值与 RocketMQ 客户端常量对齐（`DefaultMQPushConsumerImpl` L114-115）：

| 环节 | jaws-store | RocketMQ | 说明 |
|---|---|---|---|
| broker 挂起上限 | **15s** | `BROKER_SUSPEND_MAX_TIME_MILLIS` = 15s | 消费端在 `pullAsync` 里带上的期望挂起时长 |
| 消费端 RPC 超时 | **30s** | `CONSUMER_TIMEOUT_MILLIS_WHEN_SUSPEND` = 30s | 恰为挂起的 2 倍，覆盖挂起 + 扫描 + 网络 |
| provider 异步看门狗 | **25s** | 无对应物（broker 不做请求级看门狗） | jaws 侧 `DefaultProvider.orTimeout`，不配则落默认 1000ms |

**计时起点是挂起入表那一刻，不含请求处理耗时**：`HoldRequest` 构造里取 `System.currentTimeMillis() + suspendTimeoutMillis`，而它是在 `getMessageAsync` 查完消息确认 `NO_NEW_MSG` 之后才 new 的——与 RocketMQ 在 `PULL_NOT_FOUND` 分支才 `new PullRequest(..., messageStore.now(), ...)` 同形。broker 侧不 clamp 客户端传来的挂起时长，也和 RocketMQ 一致（15s 是客户端自选值，不是服务端硬限）。

三级预算严格递增：`挂起 15s（最坏 +5s 扫描兜底 = 20s）< provider 看门狗 25s < 客户端超时 30s`。两处错开都有实账：看门狗低于挂起上限时，每次空转长轮询都被误判为 40003（这条踩过——默认 1000ms 短于当时的 5s 挂起）；看门狗与客户端超时同值时，两边在同一时刻抢答，报错归因不确定。该参数是服务级的，`putAsync` 同受 25s 约束，但写路径另有 `syncFlushTimeout=5s` 内部了断，不会真等到 25s。

### 4.5 长/短轮询：一个参数两种形态，短轮询靠调度退避

没有 RocketMQ 5.0 Pop 那种具名 `longPollingEnable` 布尔开关——本模块复刻的是经典 pull hold，长/短轮询由 [getMessageAsync](../jaws-store/src/main/java/org/hongxi/jaws/store/MessageStore.java) 的 `suspendTimeoutMillis` 单参数驱动：`> 0` 时空读挂进 hold（默认 15s，见 §4.4），`<= 0` 时命中短路当场返回、不登记 hold，即短轮询。[StoreConsumer](../jaws-store/src/main/java/org/hongxi/jaws/store/client/StoreConsumer.java) 每轮把该值带上 RPC，`setSuspendTimeoutMillis(0)` 即整体退化短轮询。

要害在**泵尾重投节奏**：长轮询这轮已在 broker 阻塞到 ~15s，回来立即重投本身就是节流；短轮询空读是即时返回，若也立即重投就成了打爆 broker 的忙等。RocketMQ 的 Push 消费者恰恰不给空轮询退避（`NO_NEW_MSG → executePullRequestImmediately`），靠长轮询恒开规避高频空转，真要短轮询则交给 LitePullConsumer 由应用 `poll(timeout)` 定频驱动——且**全程无 `Thread.sleep`，所有"稍后再拉"都是 `PullMessageService` 的 `ScheduledExecutorService.schedule(delay)` 把 PullRequest 重新入队**（档位：暂停 1s / 缓存流控 50ms / broker 流控 20ms / 异常 3s）。

jaws-store 按同款语义补齐：`StoreConsumer` 的 `pumpExecutor` 本就是 `ScheduledExecutorService`（缓存阈值 50ms、错误重投 1s 已在用），仅在**短轮询且空结果**时把下一轮改为 `schedule(shortPollingIntervalMillis)`（默认 1s，对齐 RocketMQ 暂停档），其余一律立即重投——长轮询默认路径行为不变。测试见 §10 的 `shortPollingSpacesOutEmptyPulls`（靠 `getPullCount()` 数出退避后的请求密度）。

## 5. 刷盘：两种策略一个基类

先钉死数据路径的两个阶段。mmap 映射后，全局锁内那次 memcpy（`mappedByteBuffer.slice().put(...)`）**写的就是 page cache 本身**——堆外切片经页表直接映射到内核页，没有 write 系统调用，也不存在"堆 → page cache"的第二次拷贝（普通 write 路径才有这次内核拷贝）；但此时数据只是脏页，断电即丢。真正的落盘步骤是 [MappedFile.flush()](../jaws-store/src/main/java/org/hongxi/jaws/store/MappedFile.java) 里的 `fileChannel.force(false)`——mmap 场景下 fsync 的对应物（msync），把该文件脏页写回磁盘并等待设备确认，毫秒级重操作；`false` 表示不额外强刷元数据：文件长度在 map 时已定死，追加写不改文件尺寸。下表中"已进 page cache"指只完成第一阶段，两种策略里的"fsync"一律指第二阶段的 `force(false)`。

| 维度 | ASYNC_FLUSH（默认） | SYNC_FLUSH |
|---|---|---|
| 实现 | [AsyncFlushService](../jaws-store/src/main/java/org/hongxi/jaws/store/AsyncFlushService.java)：500ms 节拍器 | [GroupCommitService](../jaws-store/src/main/java/org/hongxi/jaws/store/GroupCommitService.java)：swap-list 组提交 |
| PUT_OK 语义 | 已进 page cache，持久性后台收敛 | fsync 到盘才返回 |
| 等待方式 | 无等待 | `request.future.orTimeout(5s)`，超时映射 FLUSH_TIMEOUT |
| fsync 摊薄 | 天然批量（一个节拍刷全部脏页） | 一次 `commitLog.flush()` 满足 flushOffset 之前的**所有**请求 |

### 5.1 组提交不是批量攒消息，而是并发请求分摊一次 fsync

先澄清一个易混概念：**批量（batching）是主动攒**——发送方凑够 N 条或等够 T 毫秒才发一次（如 Kafka producer 的 linger.ms），代价是人为引入等待；**组提交（group commit）是被动分摊**——写入仍是一条一条独立进行的，被"组"起来的是刷盘动作：SYNC_FLUSH 下每条消息都要求"我的数据落盘了才返回 PUT_OK"，若每条各刷一次盘，N 条 = N 次 fsync（毫秒级重操作），吞吐直接崩。

组提交的观察：fsync 按偏移刷，刷到偏移 X 就把 X 之前的所有数据都持久化了。正确性依据是**物理偏移全局单调**（CommitLog 只追加），"刷过我的末尾"对任何更早请求都是充分的持久性证明，无需 per-request fsync。[GroupCommitService](../jaws-store/src/main/java/org/hongxi/jaws/store/GroupCommitService.java) 的实际运转：

```
t0  消息 A 写完，submitFlush(nextOffset=100)，future 挂起
t1  刷盘线程取走请求，开始 commitLog.flush()
    （force(false) 把 page cache 脏页写回磁盘，毫秒级）
t2  fsync 期间 B/C/D 陆续写完，各自 submitFlush(200/300/400)
    ——进 requestsWrite 排队，没有各刷各的
t3  fsync 完成，flushedOffset=400
    → A/B/C/D 四个 future 全部 complete，一次 fsync 满足四个请求
```

swap-list（requestsWrite/requestsRead 双列表 + ReentrantLock/Condition）让提交与刷盘互不阻塞：刷盘线程持锁只做一次指针交换就取走整批，真正的 fsync 在锁外执行，期间新请求照常进 requestsWrite 自动成为下一批；完成判定即 `request.nextOffset <= flushedOffset`。

吞吐与延迟的账：并发越高，一次 fsync 摊的请求越多，单条分摊成本趋近于零（§10 实测 SYNC_FLUSH 不慢于 ASYNC_FLUSH 的成因）；低并发时一批只有一个请求，退化为逐条 fsync，延迟也不比"每条各刷"更差——每个请求最多等一次 fsync。词源是 MySQL/InnoDB 的 redo/binlog group commit（多事务共享一次日志刷盘），RocketMQ 的 GroupCommitService 是同款思路在 MQ 存储上的应用。

`MappedFileQueue.flush()` 先刷旧文件再返回最后文件的 flushed 前沿，保证返回值是真实的持久性边界。

## 6. 重启恢复：原物权威，派生物可重算

[MessageStore.start()](../jaws-store/src/main/java/org/hongxi/jaws/store/MessageStore.java) 的恢复策略刻意不做 checkpoint：

1. `commitLog.load()` 加载文件序列；
2. `recover()` 只扫描**最后一个文件**重建 wrotePosition 与每队列 nextQueueOffset（更早文件视为完整）；
3. 删除整个 consumequeue 目录，同步全量重放 CommitLog 重建索引（遇文件尾部空洞用 skipFileTail 跳过）；
4. reput 从 maxOffset 接续，各服务启动。

规模假设换来的简化：重放是 O(全量数据) 的启动成本，但省掉了 checkpoint 簿记与"索引落后多少"的一致性问题。RocketMQ 的 recover topicQueueTable + 正常/异常退出分支在此收敛为一条路径。

### 6.1 过期文件回收与磁盘水位

没有回收机制的 CommitLog 是一个会撞爆磁盘的哑雷——本模块此前 `MappedFileQueue` 从不删除文件、`ConsumeQueue.getMinOffsetInQueue()` 恒为 0，`OFFSET_ILLEGAL` 永远走不到。此次补齐为一条闭环的定期回收。

**驱动**：[MessageStore](../jaws-store/src/main/java/org/hongxi/jaws/store/MessageStore.java) 启动一个 `cleanupThread`（`startCleanupTicker()`，`cleanupIntervalMillis<=0` 则不启动），周期调 `cleanupFiles()`。两个删除严格有序：**先删 CommitLog 旧文件**（推高其 `getMinOffset()`），**再按 commitLog min offset 删 ConsumeQueue**——因为 CQ 单元只是指向 CommitLog 的偏移，只有当一条消息在 CommitLog 里已不存在，它的索引才能删；颠倒顺序会留下悬空索引。

**判据**（[MappedFileQueue.deleteExpiredFiles](../jaws-store/src/main/java/org/hongxi/jaws/store/MappedFileQueue.java)）：一个文件可删当且仅当——非队尾（`mappedFiles.size() > keepMinFiles` 保证当前活跃文件永不入删列）、已过保护位点（`fileFromOffset + fileSize <= protectFromOffset`）、且超龄或磁盘高压。关键：**文件是否滚动封住看有无后继，不看 isFull**——滞动文件因尾部留白（§3.2）`wrotePosition != fileSize`，用 `isFull()` 判合格会误将头文件排除。保护位点 `protectFromOffset = reput.getReputFromOffset()`：reput 尚未派发的字节不能删，否则新写数据丢了索引。也因此，派发失败持位期间（§4.2）该位点原地不动，回收随之停住——这是"持位换索引完整"的第二重代价；而持位越过重试上界后写侧熔断（§4.2）会阻止数据继续增长，所以停住回收不会再以撑爆磁盘收场。

**两个触发条件**（[StoreConfig](../jaws-store/src/main/java/org/hongxi/jaws/store/StoreConfig.java)）：常规按年龄（`fileRetentionMillis`，默认 72h）；磁盘吃紧时 `isDiskPressure()`（`(total - usable) * 100 / total >= diskMaxUsedSpaceRatio`，默认 90%）返回 true，本轮无视年龄强制删至 `cleanupKeepFiles`（默认 2）个地板。与 RocketMQ `DeleteMapFileService` 的 disk full 优先删除同构。

**副作用**：CommitLog 删头后 `getMinOffset()` 前推，CQ 的 `getMinOffsetInQueue()` 不再返回 0而是 `mappedFileQueue.getMinOffset() / CQ_UNIT_SIZE`，消费端拉到已回收位点时真正触发 `OFFSET_ILLEGAL`——位点重置路径从纸面变真实。测试见 §10 的 `cleanupReclaimsFilesAndAdvancesMinOffsets`。

**声明式简化**：回收以整个 mappedFile 为粒度（非 RocketMQ 的 per-queue 精细过期），且未处理"一个文件内部分记录已被 reput 消费、部分未消费"的切片竞态——protect 位点以 reput 前沿为准已是保守上界。

## 7. 薄客户端

| 组件 | 机制 | 对照 RocketMQ |
|---|---|---|
| [StoreProducer](../jaws-store/src/main/java/org/hongxi/jaws/store/client/StoreProducer.java) | 线上调用恒为 async；sync = future.get(timeout)，oneway = 只记失败日志；另提供 `sendBatchSync/sendBatchAsync` 显式批量（§7.2） | 三种发送语义同款 + 批量发送，仍不做 autoBatch/压缩/MQFaultStrategy |
| inflight 窗口 | Semaphore（默认 512），窗口满则发送方阻塞——背压的最简形态 | 对应异步发送的信号量 + 重试机制 |
| [StoreConsumer](../jaws-store/src/main/java/org/hongxi/jaws/store/client/StoreConsumer.java) | 拉取泵：每 (broker, queue) 一个泵，每次回调后继续装填下一轮 pull；batch=32，本地缓存超 1000 暂停（自身流控）；短轮询（suspend<=0）时空结果按 `shortPollingIntervalMillis` 调度退避、不忙等（§4.5） | PullMessageService + ProcessQueue 流控的最小切片 |
| broker 发现 | directUrl 逗号分隔多 broker，或注册中心 5s diff 刷新；上线建连展开订阅、下线停泵保留位点 | NameServer 路由 + rebalance 触发的最小替代（不做队列分配） |
| 位点进度 | 客户端只有 `subscription.pullOffset` 一份内存值：attach 时向 broker 问一次，每批消费完提交回去（§7.3），本地不落盘 | RocketMQ 的 `RemoteBrokerOffsetStore` 额外维护内存表，因为它的提交是 5s 攒批、需要聚合点；这里 per-batch 提交，聚合点是 pullOffset 本身 |

RPC 契约 [StoreService](../jaws-store/src/main/java/org/hongxi/jaws/store/StoreService.java) 有七个方法，均返回 CompletableFuture：`putAsync(StoreMessage)`、`putBatchAsync(List<StoreMessage>)`（显式批量，§7.2）、`pullAsync(topic, queueId, offset, maxBatch, suspendTimeoutMillis)`、`brokerInfoAsync()`（上报 brokerName，消费端位点的身份锚点），以及 `queryOffsetAsync` / `commitOffsetAsync` / `maxOffsetAsync`（§7.3：组进度读写 + 队列尾部）。长轮询的挂起对 jaws 协议就是一次"响应来得晚的普通异步调用"——provider 端全链路异步（返回 future 即挂起，不占业务线程等待）在这里拿到了真实场景，15s/30s/25s 的三级预算见 §4.4。本模块只导出这一个契约：没有额外的运维查询面，诊断靠日志（§1）。

同一 host:port 上的服务键 `group/interfaceName/version` 仅靠 interfaceName 区分——曾经把一份 `StoreAdminService` 与 `StoreService` 导出到同一端口，用以验证多 provider 同通道共存（`AbstractTransportFactory` 按 host:port 复用同一个 server）；该能力属 jaws-core，与存储引擎无关，契约已移除。

### 7.1 多 master 分片：多实例扩展，不是 HA

每个实例都是独立 master（独立 CommitLog + 稳定 brokerName），靠注册中心分片；**无复制、无 rebalance**。

- **身份**：`StoreConfig.brokerName`（默认 `broker`），首次启动写 `{storePath}/broker.identity`，重启校验，对既有数据改名直接拒启（位点绑名字）。消费端经 `brokerInfoAsync` RPC 获取身份，而非从 host:port 推导——端口漂移不丢位点。
- **发送端**：`StoreProducer(registries)` 复用 jaws cluster 负载均衡，`check=false` 允许先于 broker 启动。普通发送不承诺跨 broker 顺序；顺序发送用 `loadBalance=consistentHash`——`StoreMessage.toString()` 返回 `topic@queueId` 即哈希钩子，同一队列钉扎同一 broker。
- **消费端**：每 (broker, queue) 一条连接 + 一个泵；broker 上线建连并展开全部订阅，下线停泵但位点保留（回来续读）；位点权威在持有队列的那个 broker 上（§7.3），键 `group@topic@queueId` 天然按 broker 分治，跨 broker 同名队列互不覆盖；发现重复 brokerName 直接拒绝接入。
- **明确不做**：rebalance（每个消费实例消费所有 broker 的所有队列，一个 group 只跑一个实例）、复制/DLedger（实例宕机 = 该分片暂不可读写，已落盘消息不丢，重启续读）、retry/DLQ。
- **订阅类型 = Exclusive**（按 Pulsar 的订阅类型口径，官方文档 concepts-messaging 的 Subscription types 一节）：四式只取独占——一个 group 同时只应有一个消费者，**并行度来自队列数而不是实例数**，与 Pulsar 在 partitioned topic 下"所有分区都交给那唯一一个已连上的消费者"同构（这里的"分区"就是多 master 分片展开出的队列）。这不是随手省的，而是**位点形状定的**：消费进度是单一偏移 `group@topic@queueId -> long`，每次提交说的都是"到这条为止全做完了"，正是 Pulsar 的 cumulative acknowledgment；而 Pulsar 明确禁止 Shared / Key_Shared 用 cumulative ack（那两式必须逐条确认，因为同一订阅内有多个读者）。要支持那三式，先得有"一条消息一个 ack"的契约。至于 Failover，本模块只做到**人工接管**（§7.3 的 `secondInstanceResumesFromTheBroker`：新实例从 broker 位点续读、不重放），没有故障检测与 standby，所以不算。
- **排他是使用契约，不是服务端不变量**：Pulsar 的 Exclusive 由 broker 强制（订阅本身是"一组消费者对一个 topic 的租约"，第二个消费者并发 attach 直接报错），而 [StoreService](../jaws-store/src/main/java/org/hongxi/jaws/store/StoreService.java) 的 `pullAsync` 只带 `topic/queueId/offset`，契约里**没有任何消费者身份**，broker 无从拒绝谁。实测同组并起两个 `StoreConsumer`：各收全量（a=5、b=5），提交位点仍是 5——不报错、不分摊，只是重复投递。要做到 Pulsar 那样的强制，前置是消费者身份 + 租约（即此前搁置的心跳注册与 cidAll），否则区分不了"新消费者"与"旧消费者已死"。
- **升级注意**：本地位点文件（`{group}/offsets.properties`）与 `-Djaws.store.client.path` 一并删除，`setRemoteOffsets(false)` 退路随之消失。老部署升级后若 broker 端表里还没有这个组，就按新组从队列尾部起（§7.3），本地那份尚未消费完的旧位点不再被采纳——要保留进度只能先把旧文件的值用一次 `commitOffsetAsync` 写上去（契约公开，几行代码），否则就接受跳过。

测试见 StoreMultiBrokerTest（§10），一键演示 `./run-sample.sh store-cluster`（§9）。

### 7.2 显式批量发送：一次 RPC、一次 fsync 屏障

承接 §5.1 的概念区分——**组提交是服务端被动分摊 fsync，批量是客户端主动攒**。此次只补客户端侧的**显式批量**（应用自己决定哪些消息共担一次往返/一次刷盘屏障），仍不做服务端自动拒批的 `autoBatch`。

**一次 RPC 携带多条记录**：[StoreService.putBatchAsync(List\<StoreMessage\>)](../jaws-store/src/main/java/org/hongxi/jaws/store/StoreService.java) 一个请求带一批，回 `List<PutResult>`（与输入同序）。序列化靠 fastjson2 的 `WriteClassName` + autoType：[StoreBroker](../jaws-store/src/main/java/org/hongxi/jaws/store/StoreBroker.java) 写侧内嵌类名，读侧 `List<StoreMessage>` / `List<PutResult>` 靠白名单（`org.hongxi.jaws.` / `java.util.`）直接还原具体元素类型，无需 wrapper POJO。

**共享一次 fsync 屏障**（引擎侧 [MessageStore.putMessagesAsync](../jaws-store/src/main/java/org/hongxi/jaws/store/MessageStore.java)）：page cache 失速时整批一次回 `OS_PAGE_CACHE_BUSY`，索引熔断期间（§4.2）整批一次回 `SERVICE_NOT_AVAILABLE`，两者都发生在任何 append 之前；否则逐条 `commitLog.putMessage`（各自拿自己的 commitLogOffset），同时跟踪批内最大末尾偏移 `maxEndOffset`，SYNC_FLUSH 下只对 `maxEndOffset` 发**一次** `submitFlush`——一次 fsync 就满足整批（物理偏移全局单调，刷过末尾即刷过了前面每一条），这正是把 §5.1 组提交收益从"碰巧并发"提升为"客户端主动保证"。

**客户端语义**（[StoreProducer.sendBatchSync/sendBatchAsync](../jaws-store/src/main/java/org/hongxi/jaws/store/client/StoreProducer.java)）：inflight 窗口**一批只取一个许可**（一次 RPC 就是一个在途请求，不按条数扣），finally 释放。测试见 §10 的 `batchPutRoundTripKeepsOrder`（引擎侧保序）与 `batchSendDeliveredOverJawsProtocol`（真实协议端到端）。

### 7.3 位点上 broker：从客户端自管到服务端权威

之前的形态是**组级语义挂在广播介质上**：位点键 `brokerName@topic@queueId` 不含任何实例身份，一个 group 只有一个共享位置，可这份唯一答案只存在于某个客户端实例的本地文件里。容器重建、换机部署、起第二个实例，权威副本就跟着那个文件系统一起没了——`LocalOffsetStore.load()` 见文件不存在直接静默返回，`getOrDefault(..., 0L)` 于是把整个队列重放一遍。这一步把权威搬到 broker。

**服务端表**（[ConsumerOffsetStore](../jaws-store/src/main/java/org/hongxi/jaws/store/ConsumerOffsetStore.java)）：`ConcurrentMap<String, Long>`，键 `group@topic@queueId`，复刻 RocketMQ `ConsumerOffsetManager` 的角色。刻意留在 [MessageStore](../jaws-store/src/main/java/org/hongxi/jaws/store/MessageStore.java) 之外——位点是消费者元数据而非日志状态，不参与 put/pull 路径，也不参与持久性屏障，丢一个快照只赔重放、不赔数据；这个规模下上 KV 引擎（RocketMQ 的 `ConsumerOffsetManagerV2`）买不到东西。写入走 `merge`：`increaseOnly=true` 时取 `Math.max`，落后的写入者（乱序 RPC、或一个跑慢了的实例）不可能把已前进的组拽回去；`false` 才覆盖，留给 `OFFSET_ILLEGAL` 那种目标位点已被回收、必须后退的重置（§6.1）。持久化沿用早前客户端自管版本的形状而非 RocketMQ 的 JSON：properties + tmp + `ATOMIC_MOVE`，崩溃不会留下半张表；`offsetFlushIntervalMillis`（默认 5s，对齐 `flushConsumerOffsetInterval`）节拍刷 + shutdown 终刷，畸形行只 warn 跳过而不丢整表。

**契约**（[StoreService](../jaws-store/src/main/java/org/hongxi/jaws/store/StoreService.java)）：`queryOffsetAsync(group, topic, queueId)` 与 `commitOffsetAsync(group, topic, queueId, offset, increaseOnly)`。二者不像 `pullAsync` 那样挂起——内存表读写即刻完成，实现里直接 `completedFuture` 内联回答，不占业务线程。返回的 [OffsetResult](../jaws-store/src/main/java/org/hongxi/jaws/store/OffsetResult.java) 带 `found` 标记：**"从未提交过"与"已提交 0"必须可区分**——前者是引导新组的信号，而 0 是一个真实位置；commit 回的也是 update 后的权威值，而不是调用方自己报的数。第三个 `maxOffsetAsync(topic, queueId)` 读的是队列而不是组：永远有答案（空队列是 0），专门给"从未提交过"的组定起点，对应 RocketMQ `computePullFromWhere` 走 `CONSUME_FROM_LAST_OFFSET` 时发的 `GET_MAX_OFFSET`。它只读 CQ 前沿、不碰 CommitLog，也不占用拉取计数。

**客户端**（[StoreConsumer](../jaws-store/src/main/java/org/hongxi/jaws/store/client/StoreConsumer.java)）：`addBroker` 在挂订阅前用 `startOffsets()` 一次定完每个队列的起点——先把所有 `queryOffsetAsync` future 发出去再逐个 `get(3s)`，所以一个不可达 broker 只付一次超时而不是每队列一次；有记录就用，无记录再发一轮 `maxOffsetAsync` 取队列尾部。两轮里任何一次读不到答案就抛 `IllegalStateException`：这个 broker 保持未 attach（注册中心模式下下一轮 5s 刷新重试），因为"猜一个起点"正是这次改动要消灭的东西。消费侧 FOUND 落位后 fire-and-forget 提交 `increaseOnly=true`，`OFFSET_ILLEGAL` 重置提交 `increaseOnly=false`。提交失败只 warn：内存里的 `pullOffset` 已前进，下一轮成功提交自然补齐；若进程恰好死在这个窗口里，赔的是一段差值重放。

**为什么客户端不再留副本**：RocketMQ 的 `RemoteBrokerOffsetStore` 确实有一张内存表（`ConcurrentMap<MessageQueue, ControllableOffset>`，`updateOffset` 只写内存不发 RPC，`persistAll` 每 5s 批量落 broker），但它存在的理由是**攒批提交需要一个聚合点**——而且这个类的 `load()` 是空实现、全文没有任何文件 IO，落盘那半专属 BROADCASTING 的 `LocalFileOffsetStore`。本模块每批立即提交，聚合点就是 `pullOffset` 自身，`subscription.pullOffset` 已经是运行期驱动拉取的那份内存状态；再养一张表只会多出第二个答案，而"一个组只有一个位置"的卖点恰恰是唯一答案。

**起点引导策略**：无记录 = 新组 = 从队列尾部起，与 `CONSUME_FROM_LAST_OFFSET` 同结论——**代价是一个新组不会重放已存在的积压**（RocketMQ 用户熟悉的坑，这里按它的默认值复刻）。刻意不开放 `CONSUME_FROM_FIRST_OFFSET` / `CONSUME_FROM_TIMESTAMP`，也没有 resetOffsetByTime：要历史数据只能让应用自己拿 `pullAsync` 从 0 拉（契约本来就接受任意 offset）。仍然不做的还有：topic 删除时级联清位点（`cleanOffsetByTopic`）、广播模式 per-clientId 位点及其过期扫描（`BroadcastOffsetManager`）、位点过期清理。测试见 §10 的 ConsumerOffsetStoreTest（引擎侧 6 例）与 StoreOffsetRpcTest（协议侧 7 例）——后者 `secondInstanceResumesFromTheBroker` 断言零重放，`newGroupStartsAtTheQueueTail` 断言积压被跳过。

## 8. 协议验证发现：冷启动突发熔断案例

这是 store 作为验证件的核心产出：`COUNT=5000 MODE=async` 首跑即熔断（`No available references`），benchmark 的稳态压测从未暴露过这条路径。

**定位方法**：突发窗口短于 30ms，单次 jstack 必然错过；在 producer 启动后连续密集采样 30~40 张线程 dump，才抓到 200 个业务线程的集体阻塞现场。

**三重叠加根因**（每一重都由 dump 或对照实验钉死）：

| # | 根因 | 证据 | 修复 |
|---|---|---|---|
| 1 | fastjson2 类初始化风暴：首个 JSONB 请求触发 `JSONFactory.<clinit>`，200 线程同堵 class-init monitor | dump 中全员 `waiting on the Class initialization monitor for com.alibaba.fastjson2.JSONFactory` | [StoreBroker](../jaws-store/src/main/java/org/hongxi/jaws/store/StoreBroker.java) 导出前对 6 个 wire POJO 做序列化 round-trip 预热 |
| 2 | 零容量队列准入上限：jaws 默认 `workerQueueSize=0`，`maxSubmittedTasks = maxWorkerThreads + queueSize = 200`，在途超 200 即 fail-fast | EXHAUSTED 日志 `task: 542 (completed: 342)`；EagerThreadPoolExecutor 容量公式 | broker 配 `workerQueueSize=1000` + `minWorkerThreads=200`（prestart 全池，首波不付建线程成本） |
| 3 | 客户端无流控：tight async loop 无限放大突发 | 窗口 512 仍打满 200 线程的对照实验 | StoreProducer 加 Semaphore inflight 窗口 |

**结论**："线程池耗尽"未必是业务慢——可能是类初始化锁，也可能是准入上限；存储型 broker 的冷启动毛刺必须用突发窗口内的密集采样定位，稳态 CPU 热点与单次 dump 都会误判。修复后同一负载从"首跑熔断"到 20000 条 async 全收（§10）。

## 9. 演示说明

一键演示（broker 后台 → consumer 后台挂起等待 → producer 发送唤醒，与长轮询挂起路径对齐；每次运行独立临时数据目录，跑完即清理）：

```bash
./run-sample.sh store                                    # 1000 条 sync
COUNT=5000 MODE=async ./run-sample.sh store              # 异步发送
COUNT=5000 MODE=async SYNC_FLUSH=true ./run-sample.sh store  # 组提交刷盘
COUNT=20000 MODE=async ./run-sample.sh store             # 大流量
COUNT=2000 MODE=oneway ./run-sample.sh store             # fire-and-forget
./run-sample.sh store-cluster                            # 多 master：harbor + 双 broker + 注册中心客户端
COUNT=5000 MODE=async ./run-sample.sh store-cluster      # 多 master 异步发送
```

分步运行（`jaws-samples/jaws-sample-store`）：

```bash
# 终端 1：broker（-Dport / -DstorePath / -DsyncFlush / -DbrokerName / -Dregistry 可覆盖）
java ... org.hongxi.jaws.sample.store.StoreBrokerMain
# 终端 2：consumer（-DdirectUrl 或 -Dregistry / -Dexpected / -Dgroup / -Dtopic）——先于 producer 启动才能走挂起路径
java ... org.hongxi.jaws.sample.store.StoreConsumerMain
# 终端 3：producer（-DdirectUrl 或 -Dregistry / -Dcount / -Dmode / -Dtopic / -DloadBalance）——发送后唤醒挂起的 pull
java ... org.hongxi.jaws.sample.store.StoreProducerMain
```

多实例手动部署：每个 broker 用不同 `-DbrokerName` + `-DstorePath` + `-Dport`，共同 `-Dregistry=harbor://127.0.0.1:19848`；producer/consumer 也用 `-Dregistry` 替代 `-DdirectUrl`。

数据目录默认值：StoreConfig `~/jaws-store`；StoreBrokerMain `~/jaws-store-sample`；一键脚本用 mktemp 临时目录。位点快照在 `{storePath}/config/consumerOffsets.properties`（§7.3）——这是全链路上唯一的一份组进度，客户端不再有任何本地位点文件，重跑或重建 consumer 实例都从 broker 续读。

## 10. 验证数据

环境：macOS arm64（Apple Silicon）/ Temurin JDK 21 / loopback 直连 / fastjson2 JSONB / 单 topic 单队列 / 消息体约 10 字节。数据为 `run-sample.sh store` 实测（producer 端计时，含 RPC 往返）。

**单元测试**：35 个用例全部通过（jaws-core 272 个同时全绿）——

| 测试类 | 覆盖 |
|---|---|
| CommitLogTest（5） | 写读回环、文件滚动保内容与位点、恢复接续 queueOffset、超大消息拒绝、多队列位点独立 |
| MessageStoreTest（10） | 跨文件边界拉取、组提交满足整批、重启重放重建索引、长轮询到达唤醒、长轮询超时返回、非法位点上报、**批量回环保序（§7.2）**、清理回收文件并推进 min offset（§6.1）、**索引落空持位重试不留空洞（§4.2）**、**重试越界熔断写入并自愈（§4.2）** |
| StoreRpcTest（3） | 走真实 jaws 协议的 broker + producer + consumer 端到端（consumer 先启动触发长轮询挂起）；短轮询空结果按间隔退避、不忙等；**批量发送经协议端到端投递（§7.2）** |
| StoreMultiBrokerTest（4） | brokerName 身份持久化与改名拒启、跨 broker 位点隔离与重启续读、注册中心发现与 broker 下线后 survivor 续消费、consistentHash 队列钉扎单 broker |
| ConsumerOffsetStoreTest（6） | 缺失与 0 可区分、increaseOnly 单调不后退、重置可覆盖、组/队列键隔离、快照重启回环与文件内容、脏行不丢整表（§7.3） |
| StoreOffsetRpcTest（7） | 位点 RPC 真实协议回环（含幂等/重置/组隔离）、尾部查询回环且与组提交互不干扰（`maxOffsetAsync` 不随 commit 移动）、泵把进度提交到 broker（消费先于发送以避开新组引导）、**第二个实例从 broker 续读零重放**、**新组从队列尾部起并跳过积压**、broker 重启后快照生效、双 broker 记录互不串（§7.3） |

**吞吐实测**：

| 场景 | 条数 | 耗时 | 吞吐 | 消费完整性 |
|---|---|---|---|---|
| sync | 1000 | ~420ms | ~2.4k msg/s | 全收 |
| async | 5000 | 366ms | 13.7k msg/s | 全收 |
| async + SYNC_FLUSH | 5000 | 346ms | 14.5k msg/s | 全收 |
| async（大流量） | 20000 | 627ms | **31.9k msg/s** | 全收 |

两个值得注意的现象：

- **SYNC_FLUSH 不慢于 ASYNC_FLUSH**（14.5k vs 13.7k）：组提交把 fsync 摊薄到整批，突发下反而占优——正是精读文章里"组提交是同步刷盘高吞吐的成因"的实证；
- **吞吐随批量上升**（5000 条 13.7k → 20000 条 31.9k）：固定成本（建连、预热、JIT）被更大批量摊薄，且 inflight 窗口让管道持续满载。

## 11. 与 RocketMQ 的简化对照

| 机制 | RocketMQ | jaws-store | 简化的代价 |
|---|---|---|---|
| 文件预分配 | AllocateMappedFileService 异步预创建 + mlock | 创建时同步预热 + 启动期预分配首文件 | 滚动瞬间付预热成本（低频） |
| 恢复 | checkpoint + 正常/异常退出分支 | 只扫描末文件 + CQ 全量重放 | 启动 O(全量)，无 checkpoint 簿记 |
| 位点 | broker 端集中存储（`consumerOffset.json` / RocksDB V2）+ 客户端内存表（5s 攒批提交）+ 过期清理 + 多种起始策略 | 内存表 + properties 快照 + commit/query/maxOffset RPC（§7.3），客户端不留副本、per-batch 提交 | 无位点过期清理、无广播模式分叉、新组只能从尾部起（不可选 earliest/时间戳） |
| 消费流控 | ProcessQueue 多维阈值 | 本地缓存计数单阈值 | 够演示，不够生产 |
| 长/短轮询切换 | 经典 suspend 值驱动 + LitePullConsumer 应用 poll 定频；Pop 模式另有具名 longPollingEnable 开关 | suspend 值单参数驱动；短轮询空结果靠 ScheduledExecutor 定频退避（非 sleep） | 无 Pop 模式与具名开关 |
| 刷盘 | 异步/同步/组提交 + 刷盘链 | 节拍器 + swap-list 组提交 | 无 transientStorePool（读写分离堆外池） |
| 高可用 | 主从复制 / Dledger Raft | 无（多 master 分片不是 HA） | 单实例数据无冗余，宕机期间该分片不可用 |
| 集群组织 | broker 组主从 + NameServer 路由 + rebalance | 多 master 分片 + 注册中心发现，每实例全量消费 | 无队列级并行扩展，一个 group 一个消费实例 |
| 订阅类型 | 无此概念，改用两种 messageModel：集群消费（rebalance 把队列在实例间分摊）、广播消费（每实例全量） | 按 Pulsar 四式取 Exclusive：一组一消费者，并行度 = 队列数；位点是单一偏移，即 cumulative 确认形状（§7.1） | 服务端不强制排他（契约里无消费者身份）：同组并起两个实例不报错也不分摊，实测各收全量；且两个读者共享一个 cumulative 位点，任一方推前即全体推前——落后那台实例未处理完的区间，重启后不会再来一遍 |
| 索引落空处理 | 派发内原地重试 30 次 × 1s，仍失败则跳过该单元并 `makeLogicsQueueError` 熔断写入（RocksDB 版则抛异常、位置持住由外层 1ms 重试） | 50ms 起指数退避（上限 1s）重试同一条记录，越界（默认 30 次）则置不可写位、写入回 `SERVICE_NOT_AVAILABLE`；位点始终持住，自愈自动清位（§4.2） | 熔断只关写入口，不对外暴露健康状态（无 metrics，见"可观测"行），运维侧只能从那条 ERROR 日志发现 |
| 空间回收 | DeleteMapFileService 定期删过期文件 + disk full 优先 | mappedFile 粒度过期删除 + 磁盘水位强制删（§6.1），联动 CQ 与 min offset | 无 per-queue 精细过期 / 切片竞态，整文件为粒度 |
| 可观测 | 多维度 metrics + 外部监控对接 | 无：只有日志，加上 `getPullCount()` 一个测试探针 | 哑雷式故障（磁盘撑爆、reput 滞后、page cache 失速）只能从日志与拒收状态码反推 |
| 批量发送 | 客户端 autoBatch 自动攒批 + 批量接口 | 仅显式批量（一次 RPC 共享一次 fsync 屏障，§7.2） | 无服务端自动攒批 |

每一处简化都是声明式的：范围钉死在存储内核 + 薄客户端，把"为什么这样设计"留给五篇精读文章，把"最小可运行实现"留在代码里。

## 12. 补充知识：缺页（Page Fault）

§3.3 的 mmap 预热与 §5 的刷盘都反复提到"缺页"，这里补一段底层机理，便于理解那两个成本为什么存在、为什么要挪出写入路径。

### 12.1 虚拟内存与按需分页

进程看到的是连续的虚拟地址空间，而非物理内存。内核把它切成固定大小的**页**（Linux 通常 4KB），用页表维护"虚拟页 → 物理页框"的映射。关键在于映射是**按需建立**的：`mmap` 一个文件（或 `malloc` 一块内存）只是登记"这段地址将来对应什么"，并不立刻分配物理页、也不填页表项。

CPU 访问某地址时由 MMU 查页表翻译。若目标页当前没有有效映射，硬件触发异常陷入内核——这就是**缺页中断**：内核分配物理页框、建立映射（文件映射则关联到 page cache 里的页），必要时发起磁盘读，然后回用户态重执行那条指令。全程对应用透明，但代价高——用户态到内核态的切换走系统调用路径，微秒级，比一次普通内存访问（纳秒级）慢几个数量级，且缺页期间**线程被挂起**。

### 12.2 三类缺页

| 类型 | 触发场景 | 代价 |
|---|---|---|
| 无效访问 | 地址根本没映射（越界、NULL） | 直接 SIGSEGV，不恢复 |
| 主缺页 major | 页需从磁盘读入（mmap 冷数据、被回收后又访问） | 最贵，含磁盘 IO |
| 次缺页 minor | 数据已在 page cache，只需分配页框 + 建映射 | 无 IO 但仍陷内核；写新页还要先清零 |

本模块关心的是第三类：`mmap` 一个新 commitlog 文件后，**第一次**触碰每一页都是一次 minor fault——内核要分配页框、把稀疏文件的实际尺寸撑到该页、建页表项。

### 12.3 回扣 §3.3：为什么预热要 write-then-force

§3.3 新建文件时逐页（4KB 步进）写零，正是主动把上述 minor fault 一次性提前触发掉：

- **不预热**：虚拟映射建好但物理页全缺，第一条真实 append 落到哪页就在哪页现场缺页——写线程卡在内核分配页框/扩展文件，而这发生在持有全局 `putMessageLock` 的临界区里，直接制造延迟毛刺；§3.2 的 page cache 失速探针（持锁超 1s 判忙）抓的就是这类卡顿。
- **预热不 force**：写零让每一页都变成脏页留在 page cache，第一次真实 fsync 要背整个文件的写回（§5 的第二阶段），组提交首批直接超时。

所以"逐页写零 + `force(false)`"两步 = 把**缺页成本**和**脏页写回成本**都从写入/fsync 关键路径挪到文件创建阶段。Linux 上的等价手段是 `madvise(MADV_POPULATE_WRITE)` 或 `MAP_POPULATE` 一次性预触发，RocketMQ `warmMappedFile` 的 mlock 意图相同。