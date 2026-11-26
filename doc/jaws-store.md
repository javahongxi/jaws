# jaws-store：RocketMQ 风格存储引擎的设计与协议验证

jaws-store 是 jaws 仓库内的存储引擎实践模块（约 2800 行 main + 560 行测试），复刻 RocketMQ 存储内核的核心机制：mmap CommitLog 顺序写、ConsumeQueue 定长索引、异步/组提交刷盘、长轮询挂起唤醒。它的定位不是 MQ 产品线，而是 **jaws 协议的高吞吐实战验证件**——类比 harbor 之于 wire 协议的 Nacos 兼容性验证，store 用真实 MQ 负载去压 jaws benchmark 压不到的路径。

五篇前置精读（[发送链路](rocketmq/rocketmq-发送链路精读.md)、[消费链路](rocketmq/rocketmq-消费链路精读.md)、[刷盘与复制](rocketmq/rocketmq-刷盘与复制精读.md)、[挂起请求唤醒链路](rocketmq/rocketmq-挂起请求唤醒链路.md)、[高性能高吞吐成因分析](rocketmq/rocketmq-高性能高吞吐成因分析.md)）是它的设计文档；本文反过来讲实现落了什么、简化了什么、验证出了什么。

## 1. 定位与边界

| 维度 | 做 | 不做（声明式简化） |
|---|---|---|
| 存储内核 | mmap CommitLog + ConsumeQueue + 异步 reput 建索引 | IndexFile、多路径存储、磁盘水位清理 |
| 持久性 | 异步刷盘节拍器 + SYNC_FLUSH 组提交 | HA 主从复制、Dledger |
| 消费模型 | 长轮询拉取、批量拉取、客户端自管位点（键含 brokerName） | Pop 消费、rebalance、broker 端位点存储 |
| 消息类型 | 普通消息 | 事务消息、延迟消息、顺序消息锁 |
| 接入 | jaws 协议直连或注册中心发现（多 master 分片，§7.1） | NameServer 专用路由、topic 路由下发 |
| 客户端 | sync/async/oneway 发送、拉取泵、inflight 流控 | autoBatch、压缩、MQFaultStrategy |

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
| [client/](../jaws-store/src/main/java/org/hongxi/jaws/store/client/) | StoreProducer / StoreConsumer / LocalOffsetStore / MessageListener |

数据目录：`{storePath}/commitlog/` 放物理日志（文件名为 20 位零填充起始偏移，默认单文件 1GB），`{storePath}/consumequeue/{topic}/{queueId}/` 放索引（默认 300000 单元 × 20B = 6MB/文件）。

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

### 4.2 先建索引，后发通知

[MessageStore.onDispatch](../jaws-store/src/main/java/org/hongxi/jaws/store/MessageStore.java) 严格两步：先 `putMessagePositionInfo` 落 CQ 单元，再 `notifyMessageArrive`。顺序颠倒会让被唤醒的 pull 查不到索引而空转——这是 RocketMQ doDispatch → notifyMessageArriveIfNecessary 顺序约束的直接复刻。

### 4.3 挂起/唤醒的竞态防护

[PullRequestHoldService](../jaws-store/src/main/java/org/hongxi/jaws/store/PullRequestHoldService.java) 三道防线：

1. **HoldRequest.completed（AtomicBoolean CAS）**：唤醒与超时扫描两条路径只允许一条 complete future；
2. **挂起后 re-check**：[MessageStore.getMessageAsync](../jaws-store/src/main/java/org/hongxi/jaws/store/MessageStore.java) 注册挂起后立即重查 maxOffset——空读与挂起之间到达的消息，其通知已被错过，re-check 关闭这个窗口；
3. **周期扫描兜底**：deadline 到期或 maxOffset 前进都会触发 wakeup，通知丢失不致命。

延迟趋近 push，成本趋近 pull：broker 不维护任何 per-consumer 推送状态。

### 4.4 挂起时长与三级超时预算

数值与 RocketMQ 客户端常量对齐（`DefaultMQPushConsumerImpl` L114-115）：

| 环节 | jaws-store | RocketMQ | 说明 |
|---|---|---|---|
| broker 挂起上限 | **15s** | `BROKER_SUSPEND_MAX_TIME_MILLIS` = 15s | 消费端在 `pullAsync` 里带上的期望挂起时长 |
| 消费端 RPC 超时 | **30s** | `CONSUMER_TIMEOUT_MILLIS_WHEN_SUSPEND` = 30s | 恰为挂起的 2 倍，覆盖挂起 + 扫描 + 网络 |
| provider 异步看门狗 | **25s** | 无对应物（broker 不做请求级看门狗） | jaws 侧 `DefaultProvider.orTimeout`，不配则落默认 1000ms |

**计时起点是挂起入表那一刻，不含请求处理耗时**：`HoldRequest` 构造里取 `System.currentTimeMillis() + suspendTimeoutMillis`，而它是在 `getMessageAsync` 查完消息确认 `NO_NEW_MSG` 之后才 new 的——与 RocketMQ 在 `PULL_NOT_FOUND` 分支才 `new PullRequest(..., messageStore.now(), ...)` 同形。broker 侧不 clamp 客户端传来的挂起时长，也和 RocketMQ 一致（15s 是客户端自选值，不是服务端硬限）。

三级预算严格递增：`挂起 15s（最坏 +5s 扫描兜底 = 20s）< provider 看门狗 25s < 客户端超时 30s`。两处错开都有实账：看门狗低于挂起上限时，每次空转长轮询都被误判为 40003（这条踩过——默认 1000ms 短于当时的 5s 挂起）；看门狗与客户端超时同值时，两边在同一时刻抢答，报错归因不确定。该参数是服务级的，`putAsync` 同受 25s 约束，但写路径另有 `syncFlushTimeout=5s` 内部了断，不会真等到 25s。

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

## 7. 薄客户端

| 组件 | 机制 | 对照 RocketMQ |
|---|---|---|
| [StoreProducer](../jaws-store/src/main/java/org/hongxi/jaws/store/client/StoreProducer.java) | 线上调用恒为 async；sync = future.get(timeout)，oneway = 只记失败日志 | 三种发送语义同款，去掉 autoBatch/压缩/MQFaultStrategy |
| inflight 窗口 | Semaphore（默认 512），窗口满则发送方阻塞——背压的最简形态 | 对应异步发送的信号量 + 重试机制 |
| [StoreConsumer](../jaws-store/src/main/java/org/hongxi/jaws/store/client/StoreConsumer.java) | 拉取泵：每 (broker, queue) 一个泵，每次回调后继续装填下一轮 pull；batch=32，本地缓存超 1000 暂停（自身流控） | PullMessageService + ProcessQueue 流控的最小切片 |
| broker 发现 | directUrl 逗号分隔多 broker，或注册中心 5s diff 刷新；上线建连展开订阅、下线停泵保留位点 | NameServer 路由 + rebalance 触发的最小替代（不做队列分配） |
| [LocalOffsetStore](../jaws-store/src/main/java/org/hongxi/jaws/store/client/LocalOffsetStore.java) | **客户端自管位点**（Kafka 风格）：Properties 文件 + temp file 原子 move，键 `brokerName@topic@queueId` | 声明式偏离：不做 broker 端位点存储 |

RPC 契约 [StoreService](../jaws-store/src/main/java/org/hongxi/jaws/store/StoreService.java) 只有三个方法，均返回 CompletableFuture：`putAsync(StoreMessage)`、`pullAsync(topic, queueId, offset, maxBatch, suspendTimeoutMillis)` 与 `brokerInfoAsync()`（上报 brokerName，消费端位点的身份锚点）。长轮询的挂起对 jaws 协议就是一次"响应来得晚的普通异步调用"——provider 端全链路异步（返回 future 即挂起，不占业务线程等待）在这里拿到了真实场景，15s/30s/25s 的三级预算见 §4.4。

### 7.1 多 master 分片：多实例扩展，不是 HA

每个实例都是独立 master（独立 CommitLog + 稳定 brokerName），靠注册中心分片；**无复制、无 rebalance**。

- **身份**：`StoreConfig.brokerName`（默认 `broker`），首次启动写 `{storePath}/broker.identity`，重启校验，对既有数据改名直接拒启（位点绑名字）。消费端经 `brokerInfoAsync` RPC 获取身份，而非从 host:port 推导——端口漂移不丢位点。
- **发送端**：`StoreProducer(registries)` 复用 jaws cluster 负载均衡，`check=false` 允许先于 broker 启动。普通发送不承诺跨 broker 顺序；顺序发送用 `loadBalance=consistentHash`——`StoreMessage.toString()` 返回 `topic@queueId` 即哈希钩子，同一队列钉扎同一 broker。
- **消费端**：每 (broker, queue) 一条连接 + 一个泵；broker 上线建连并展开全部订阅，下线停泵但位点保留（回来续读）；位点键 `brokerName@topic@queueId`，跨 broker 同名队列互不覆盖；发现重复 brokerName 直接拒绝接入。
- **明确不做**：rebalance（每个消费实例消费所有 broker 的所有队列，一个 group 只跑一个实例）、复制/DLedger（实例宕机 = 该分片暂不可读写，已落盘消息不丢，重启续读）、retry/DLQ。
- **升级注意**：旧位点键 `topic@queueId` 与新键不匹配，被忽略——升级后首轮从 0 重消费一次。

测试见 StoreMultiBrokerTest（§10），一键演示 `./run-sample.sh store-cluster`（§9）。

## 8. 协议验证发现：冷启动突发熔断案例

这是 store 作为验证件的核心产出：`COUNT=5000 MODE=async` 首跑即熔断（`No available references`），benchmark 的稳态压测从未暴露过这条路径。

**定位方法**：突发窗口短于 30ms，单次 jstack 必然错过；在 producer 启动后连续密集采样 30~40 张线程 dump，才抓到 200 个业务线程的集体阻塞现场。

**三重叠加根因**（每一重都由 dump 或对照实验钉死）：

| # | 根因 | 证据 | 修复 |
|---|---|---|---|
| 1 | fastjson2 类初始化风暴：首个 JSONB 请求触发 `JSONFactory.<clinit>`，200 线程同堵 class-init monitor | dump 中全员 `waiting on the Class initialization monitor for com.alibaba.fastjson2.JSONFactory` | [StoreBroker](../jaws-store/src/main/java/org/hongxi/jaws/store/StoreBroker.java) 导出前对 5 个 wire POJO 做序列化 round-trip 预热 |
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
# 终端 2：producer（-DdirectUrl 或 -Dregistry / -Dcount / -Dmode / -Dtopic / -DloadBalance）
java ... org.hongxi.jaws.sample.store.StoreProducerMain
# 终端 3：consumer（-DdirectUrl 或 -Dregistry / -Dexpected / -Dgroup / -Dtopic）
java ... org.hongxi.jaws.sample.store.StoreConsumerMain
```

多实例手动部署：每个 broker 用不同 `-DbrokerName` + `-DstorePath` + `-Dport`，共同 `-Dregistry=harbor://127.0.0.1:19848`；producer/consumer 也用 `-Dregistry` 替代 `-DdirectUrl`。

数据目录默认值：StoreConfig `~/jaws-store`；StoreBrokerMain `~/jaws-store-sample`；一键脚本用 mktemp 临时目录。位点文件在 `~/jaws-store-client/{group}/offsets.properties`（`-Djaws.store.client.path` 可覆盖），重跑 consumer 从上次位点续读。

## 10. 验证数据

环境：macOS arm64（Apple Silicon）/ Temurin JDK 21 / loopback 直连 / fastjson2 JSONB / 单 topic 单队列 / 消息体约 10 字节。数据为 `run-sample.sh store` 实测（producer 端计时，含 RPC 往返）。

**单元测试**：16 个用例全部通过——

| 测试类 | 覆盖 |
|---|---|
| CommitLogTest（5） | 写读回环、文件滚动保内容与位点、恢复接续 queueOffset、超大消息拒绝、多队列位点独立 |
| MessageStoreTest（6） | 跨文件边界拉取、组提交满足整批、重启重放重建索引、长轮询到达唤醒、长轮询超时返回、非法位点上报 |
| StoreRpcTest（1） | 走真实 jaws 协议的 broker + producer + consumer 端到端（consumer 先启动触发长轮询挂起） |
| StoreMultiBrokerTest（4） | brokerName 身份持久化与改名拒启、跨 broker 位点隔离与重启续读、注册中心发现与 broker 下线后 survivor 续消费、consistentHash 队列钉扎单 broker |

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
| 位点 | broker 端集中存储 + 提交链路 | 客户端本地 Properties | 无跨实例位点一致性 |
| 消费流控 | ProcessQueue 多维阈值 | 本地缓存计数单阈值 | 够演示，不够生产 |
| 刷盘 | 异步/同步/组提交 + 刷盘链 | 节拍器 + swap-list 组提交 | 无 transientStorePool（读写分离堆外池） |
| 高可用 | 主从复制 / Dledger Raft | 无（多 master 分片不是 HA） | 单实例数据无冗余，宕机期间该分片不可用 |
| 集群组织 | broker 组主从 + NameServer 路由 + rebalance | 多 master 分片 + 注册中心发现，每实例全量消费 | 无队列级并行扩展，一个 group 一个消费实例 |

每一处简化都是声明式的：范围钉死在存储内核 + 薄客户端，把"为什么这样设计"留给五篇精读文章，把"最小可运行实现"留在代码里。