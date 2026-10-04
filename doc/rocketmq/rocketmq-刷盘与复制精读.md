# RocketMQ 刷盘与复制精读（5.5）

> 主题边界：从 `CommitLog.handleDiskFlushAndHA` 往后的全部——刷盘 service 家族、超时兜底、HA 数据面往返与 controller 共识层考证。
> 发送链路（client→store append）见姊妹篇《rocketmq-发送链路精读》，其 §4.4 只留概要。
> 口径：行号对照本机 `~/github/rocketmq`（5.5.1-38-g78b96bc5e）逐行核验；默认值取自本机 `MessageStoreConfig` 源码行；运行时行为在 5.5.0-bin-release 实跑印证（默认 ASYNC_MASTER + ASYNC_FLUSH 组合）。

---

## 1. 入口：handleDiskFlushAndHA 的 fork-join（CommitLog L1360-1379）

```java
CompletableFuture<PutMessageStatus> flushFuture   = handleDiskFlush(appendResult, msg);
CompletableFuture<PutMessageStatus> replicaFuture = needHandleHA
        ? handleHA(appendResult, putResult, needAckNums)
        : completedFuture(PUT_OK);

return flushFuture.thenCombine(replicaFuture, (f, r) -> {
    if (f != PUT_OK) putResult.setPutMessageStatus(f);
    if (r != PUT_OK) putResult.setPutMessageStatus(r);   // 复制状态覆盖刷盘状态
    ...});
```

- **并行是本质**：本地 fsync 与网络推从库是两个独立慢 IO，thenCombine 后总耗时 = max，串行则相加。
- **状态合并顺序有语义**：都失败时报"从库没跟上"比"盘没刷上"信息量更大（复制蕴含从库侧刷盘）。
- **全程无阻塞**：组合 future 交给 broker `thenAcceptAsync(putMessageFutureExecutor)`（链路篇 §3.4）。三层拼出全链路异步：client"同步=异步+get"→ broker"return null 释放线程"→ store"刷盘复制全 future 化"。
- **化石层**：接口双签名并存（FlushManager L33 void 旧版 / L35 future 新版），旧版 `future.get(syncFlushTimeout)`（DefaultFlushManager L2258）会占住发送线程，5.x 异步化后仅剩兼容意义。

## 2. 进不进复制等待：三个门 + 预检（L1047-1066 / needHandleHA）

1. **门1 生产者弃权**：`waitStoreMsgOK==false`（`PROPERTY_WAIT_STORE_MSG_OK`）→ 跳。
2. **门2 duplication**：`isDuplicationEnable()` → 跳（复制外包给容灾/云管道）。
3. **门3 角色**：`BrokerRole != SYNC_MASTER` → 跳；进了 `handleHA` 后 `needAckNums ∈ [0,1]` 仍立即 PUT_OK（L1387）。

> **配置大坑（已验源码默认值）**：`inSyncReplicas` 默认 **1**（MessageStoreConfig L384）、`enableAutoInSyncReplicas` 默认 **false**（L404）。**光配 `brokerRole=SYNC_MASTER` 不会等任何从库**——ack 计数含 master 自己，须 `inSyncReplicas≥2` 才是真同步复制。"SYNC_MASTER 却静默不复制"是生产配错的活标本。

**两种模式的写前预检**（凑不齐人就在 append 之前拒，不让每条消息干等超时）：

- **controller**：`inSyncReplicasNums(curr) < minInSyncReplicas` → `IN_SYNC_REPLICAS_NOT_ENOUGH`（L1051）；`allAckInSyncStateSet` → needAckNums=-1。
- **slaveActingMaster**：`enableAutoInSyncReplicas=true` 时 `needAckNums = clamp(配置值, minInSync, aliveInSync)`（calcNeedAckNums）——从库掉线自动放宽要求**保写入**，CAP 滑杆；仍不足 → 预检拒写。
- in-sync 成员判定按 **gap**：`masterPutWhere - slaveAckOffset < haMaxGapNotInSync`（DefaultHAService L201-204），Kafka ISR 同款思想。

## 3. 刷盘座位：两个座位三个 service

数据流：**append（页缓存/池内） ──commit──► pageCache ──flush──► 盘**。

```java
// DefaultFlushManager 构造 L2227-2235
flushCommitLogService = SYNC_FLUSH ? new GroupCommitService() : new FlushRealTimeService();  // 座位A·二选一
commitRealTimeService = new CommitRealTimeService();   // 座位B·构造无条件，start() 仅在开池时启动（L2241）
```

### 3.1 GroupCommitService（SYNC_FLUSH）——请求驱动的组提交（L1705-1811）

- 双缓冲 `requestsWrite/Read` + `PutMessageSpinLock` swap（`onWaitEnd` 时换引用）；`putRequest` 塞入即 `wakeup()`。
- `doCommit()`：逐请求最多 **1000 轮** `mappedFileQueue.flush(0)`，不中 `sleep(1)`（L1741-1743 注释：等池 commit 追上）→ `wakeupCustomer(PUT_OK / FLUSH_DISK_TIMEOUT)`。
- **摊还本质**：offset 全局单调，刷到批内最大 nextOffset 的一次 fsync 顺带满足全部前面请求——组提交=fsync 成本摊还（一次 fsync 服务一批请求）。
- 空队列也 `flush(0)` 一轮（L1763）；shutdown 时循环 flush(0) 兜底（L1789）。
- `syncFlushTimeout=5s`（L257）。

### 3.2 FlushRealTimeService（ASYNC_FLUSH，默认）——叫不醒的节拍器（L1578-1662）

- 参数（均已验默认值）：`flushIntervalCommitLog=500ms`(L150)、`flushCommitLogLeastPages=4`(L214)、`thoroughInterval=10s`(L221)——每 500ms 刷，脏页<4 页跳过，但 10s 必来一次 leastPages=0 的全量。**异步刷盘丢失窗口的上界就是这套节拍**。
- **关键暗坑**：`flushCommitLogTimed=true`(L170) → 走 `Thread.sleep(interval)`（L1606-1607）而**非** `waitForRunning`——**任何 wakeup() 都叫不醒它**；`wakeFlushWhenPutMessage=false`(L365) 只是双重保险。推论：CommitRealTime L1557 那个"commit 完顺手 wakeUpFlush"的接驳，**默认配置下名存实亡**，池里数据到盘仍等 500ms 心跳。接驳生效需 `flushCommitLogTimed=false`。
- ASYNC 分支的写请求 future 在 `handleDiskFlush` 当场 `completedFuture(PUT_OK)`（L2300-2311）——**异步刷盘路径上 FLUSH_DISK_TIMEOUT 不可能出现**；PUT_OK 语义 = 进了 pageCache，断电损失由 OS 兜底。

### 3.3 CommitRealTimeService（仅 transientStorePool）——池→页缓存搬运工（L1523-1576）

- `transientStorePoolEnable` 默认 false（L274）→ 不开池此线程根本不启动（构造了但没 start）。
- `commitIntervalCommitLog=200ms`(L155)、`commitLeastPages=4`(L216)、`commitThoroughInterval=200ms`(L222)——**thorough 与 interval 相等，页门槛实际只在提前唤醒的场景起作用**，常态每 200ms 全量 commit。
- `wakeCommitWhenPutMessage=true`(L363)：写线程可提前叫醒 commit（开池时低延迟接力的关键），对应 `handleDiskFlush` L2306-2308。
- L1555-1557：本轮真 commit 了数据（`!result`，注释原话 "result = false means some data committed"）→ 更新时戳并 `wakeUpFlush()`（但见 §3.2 暗坑）。

### 3.4 FlushDiskWatcher

把请求交给一个独立的超时看门狗线程。因为这条路径不阻塞等待刷盘结果,所以需要一个旁观者来保证"同步刷盘超时"这一语义:它能给 future 兜底 complete(超时状态),避免刷盘卡死时请求永久悬挂。

### 3.5 座位组合 2×2

| | 无池（默认） | transientStorePool |
|---|---|---|
| **ASYNC_FLUSH（默认）** | FlushRealTime 独舞（500ms/10s） | Commit(200ms·可唤醒) + Flush(500ms) |
| **SYNC_FLUSH** | GroupCommitService 独舞（请求驱动，Watcher 兜超时） | Commit + GroupCommit（doCommit 的 `sleep(1)` 就是等接力） |

## 4. 复制座位：数据面往返全链路 + GroupTransferService 的三种 ack 口径

> 与 §3"先线程模型再逐个 service"对称，这节按**握手 → 稳态往返 → 活性自愈**三步走。类/行号全部 5.5.1 本机验证。先立一个容易混淆的前提：**HA 与业务 remoting 是完全两栈**——master/slave 两端都是裸 `Selector + SocketChannel` 的独立 NIO 线程，与 Netty pipeline 无关，HA 流量不占业务 event loop 一个核。

### 4.1 分工地基：一根连接上的六个角色

| 端 | 角色 | 干什么 |
|---|---|---|
| master | `DefaultAcceptSocketService`（DefaultHAService L70/298-313） | 监听 `haListenPort`（显式配置，0=OS 随机挑），accept 从库 |
| master | `WriteSocketService`（每连接一个，DefaultHAConnection L256） | 推数据 + 零体心跳帧 |
| master | `ReadSocketService`（每连接一个，L137） | 收 8B 上报，记 `slaveAckOffset` |
| master | `GroupTransferService`（全局一个，ha 包 L38） | 等票 + 通知（doWaitTransfer，L79-146） |
| slave | `DefaultHAClient`（单线程状态机，L303-345） | 连接/上报/收数/追加，READY→TRANSFER 循环 |
| 发送路径 | `handleHA`（CommitLog L1385-1399） | 提交 GroupCommitRequest 进 transfer 双缓冲 + `wakeupAll` |

### 4.2 握手与对齐：一张 8 字节的"成绩单"

- slave `connectMaster`（L251-268）：连上后 **`currentReportedOffset` 取本地 `maxPhyOffset`（L264）**——首条 8B 上报就是握手的全部："我到这了，从这里续"。
- master 首帧决策（WriteSocketService L282-306）：等 `slaveRequestOffset` 就绪；**=0（空从库）→ 对齐到 1G 文件边界起推**（maxOffset 减模数，L289-299）；非 0 → 从上报位点直接追。
- 没有 epoch、没有截断协商——Default 路线的握手就一个数字，敢这么简是因为**从库从不自己写**（appendData 只跟着主推的物理地址走），尾巴天然对齐；脏尾问题在"换主"才出现，那是 controller 模式下 `AutoSwitchHAService` 的 epoch 截断机制的事，本篇不展开。

### 4.3 稳态往返：推 → 收落 → 回报 → 收报

**推（master WriteSocketService，L278-375）**：

1. 距上次写超 `haSendHeartbeatInterval=5s`（Config L242）先发**零体帧**（12B 头 `phyOffset|0`，L308-326）——保活并刷新从库侧的 lastReadTimestamp；
2. `getCommitLogData(nextTransferFromWhere)`（L333，mmap 切片零拷贝引用）→ 单帧不超 `haTransferBatchSize=32KB`（L337）→ `FlowMonitor` 秒级限速（L341-350，警告日志 1s 节流）→ 写 12B 头 + body，游标前进；
3. 没数据时 `waitNotifyObject.allWaitForRunning(100)`（L368）挂 100ms——`handleHA` L1398 那声 `wakeupAll` 唤醒的正是全体推线程：**有同步复制任务要追进度时，没人需要再等满节拍**。

**收落（slave）**：

1. `transferFromMaster`（L347-365）：到点主动回报（`isTimeToReportOffset`，L105-108）→ `select(1000)` → 读事件；
2. `dispatchReadRequest`（L184-228）：解析 12B 头后**连续性断言——帧内 masterPhyOffset ≠ 本地 maxPhyOffset 即 return false 断线**（L195-198）：不解释、不自愈，交给重连重新对齐，粗暴但绝不吃错帧；
3. 整帧齐了 `appendToCommitLog(masterPhyOffset, ...)`（L207-208）→ `commitLog.appendData`（DefaultMessageStore L1379）：**同样抢那把全局 putMessageLock、按主推来的物理地址顺序落 mmap**——从库的 commitlog 是主库的字节级镜像布局，这正是 §4.4"从库 CQ 自己派发但内容恰好一致"的前提；
4. 每落完一帧 `reportSlaveMaxOffsetPlus()`（定义 L231-243，逐帧调用点 L213）：本地 maxPhy 前进了就**立刻**回写 8B——ack 是事件驱动，不是定时。

**收报（master ReadSocketService，L156-253）**：`select(1000)` → 读满 8B → `slaveAckOffset = readOffset`（L230，首次顺带记 `slaveRequestOffset`）→ `notifyTransferSome`（L236）唤醒 GroupTransferService 去数票。

### 4.4 等待语义：一个 DTO 三种口径

复用 `CommitLog.GroupCommitRequest`（multi-ack 构造 L1676），塞进 GroupTransferService 自己的双缓冲——与刷盘座位共享同一个"等 offset 达成"原语。`doWaitTransfer` 在 deadline 内 1ms spin（L87-89）：

| 口径 | 判据 | 语义 |
|---|---|---|
| `ackNums<=1` | `push2SlaveMaxOffset ≥ nextOffset` | master 推出去即算，不管从库落盘 |
| `ackNums==-1`（controller） | master 1 票 + syncStateSet 内连接 ack≥next | 等 syncStateSet 全员 |
| 常规 N | master 1 票 + 遍历 `slaveAckOffset≥nextOffset` 凑票 | in-sync 指定数 |

- **传输与等待解耦**：连接上的读写线程只管搬字节、记位点（I/O 密集）；"够数了没 + 通知上层"全在 GroupTransferService（doWaitTransfer 只读连接上的 volatile `slaveAckOffset`，不碰 socket）——ack 状态由 I/O 线程生产、等待线程消费。
- 超时 → `FLUSH_SLAVE_TIMEOUT`（L141）。**5.5 常规路径已不产生 `SLAVE_NOT_AVAILABLE`**（枚举只剩 timer store）：无从库时表现为凑不齐票的超时——**"从库不存在"被伪装成"从库很慢"**，这正是 §2 预检存在的理由。

### 4.5 活性与自愈：housekeeping 双向对等，重连是唯一退路

- master 侧：读线程静默超 `haHousekeepingInterval=20s`（Config L243）→ break 拆连接（L165-169），收尾五连：置 SHUTDOWN → 拖写线程 down → `removeConnection` → 计数-- → 关 selector/socket（L176-198）。
- slave 侧：对称检查"主的最近供数"（L330-336）→ closeMaster 回 READY 立即重连；连不上才退避 5s（L317）。
- **重连即全量再对齐**：`connectMaster` 重新取成绩单、master 侧新连接重走 4.2 首帧决策。设计上把"新连接"复用成唯一恢复路径，没有原地修复协议——与 §3.4 watcher"执行者挂掉另有哨兵"的思路一致：**坏掉的部分整个扔掉重来，不留半坏状态**。

## 5. 衍生物的持久化：CQ 不 sync、不复制、可重放

**CQ 的刷盘：自有线程，不在 SYNC_FLUSH 主线里。** 专属 `FlushConsumeQueueService`（5.5.1 位于 `queue/ConsumeQueueStore` 内部，L668）：节拍 `flushIntervalConsumeQueue=1000ms`（Config L172），遍历 consumeQueueTable 全表 `cq.flush(leastPages)`，每 thoroughInterval 强制全量并把 `logicsMsgTimestamp/logicsPhysicalOffset` 回写 checkpoint，shutdown 时 leastPages=0 刷净。主刷盘体系与它零交集：GroupCommitService 刷的是 commitlog 的 mappedFileQueue，FlushDiskWatcher 守的只是 GroupCommitRequest——CQ 落盘永远只有这条 1s 节拍线。

**CQ 的复制：根本不走网络。** HA 推方数据源是 `getCommitLogData(nextTransferFromWhere)`（DefaultHAConnection L334），管道里流的全是 commitlog 字节；收方 `commitLog.appendData`（DefaultMessageStore L1379）。从库的 CQ 由**从库自己的 ReputMessageService** 从收到的 commitlog 派发出来——doReput 里 `BrokerRole.SLAVE` 才累计 storeStats 的分支（L2753-2759）即其在从库身上运行的铁证。

**设计正当性：CQ 是 commitlog 的函数。** 丢了就从 checkpoint 位点重放 rebuild——所以它不配持久性承诺（sync flush）、不配复制承诺（HA）：原文强一致，衍生物随时可重算；checkpoint 里"逻辑队列时间戳"也是 CQ flush **成功后**才回写——派生状态汇报派生进度。同理，换主时的 Catchup 截断也只截本地 CQ，重放自修。

**一个容易误读的现象**：主从两台机器上的 CQ 文件是各自独立长出来的，内容"恰好一致"不是因为同步，而是因为 **commitlog 一致 + 派发确定性（同序同算法）**。

## 6. 生命周期：删除阶梯与背压终态（无消费者、一直生产会怎样）

**内存里没有"攒消息"这回事**。三层都不随 backlog 增长：① JVM 堆内只有 `mappedFiles` 链表 + 每文件一个 MappedFile 元对象（数量=保留量÷1G，常数级）；② 物理驻留全在 OS page cache，写=脏页、回写后成 clean page、内存压力被内核 LRU 回收——驻留量由内核水位决定而非生产速度；③ transientStorePool 是固定 5×1G 池（Config L275，默认关），池满即拒写。堆积的真实去处只有一个：**磁盘文件数**。

**删除阶梯**（CleanCommitLogService，`cleanResourceInterval=10s` 一轮，默认值均验）：

```
fileReservedTime=72h          正常按 TTL 删最老文件（deleteWhen=04 只是定点补删）
diskMaxUsedSpaceRatio=75%     超线提前删（不等 TTL）
diskSpaceCleanForciblyRatio=85%  超线强制删（连 TTL 都不顾）
diskSpaceWarningLevelRatio=90%   超线 runningFlags.getAndMakeDiskFull()（L2448）
```

disk-full 位置起后写侧双闸门拒单：send hook 查 `isWriteable()`（HookUtils L76）+ MappedFile append 门（DefaultMappedFile L527）——与 §2 门区的 pageCacheBusy / transientPool 拒写同族。**背压终态不是 OOM，是"删数据 → 删无可删 → 拒写"**。

**删除的三方联动**：① 内存——MappedFile 同步 destroy（unmap+close+出列），链表长度被删除策略锁死；② 衍生物——CQ 文件同策略删 + `correctMinOffset` 抬 minOffset，index 作废可重建；③ 消费者——位点 k < 新 minOffset → 钳到 minOffset 照发（§5 链路的闭环）：**没消费到的消息静默消失**。

> 一句话：MQ 用"数据完整性可牺牲"换"可用性死保"——72h TTL 是明码标价的遗忘承诺；生产者无视消费速率的代价不是自己被拖慢，而是消息被时间吃掉。

## 7. controller：主从切换的唯一大脑

它最重要的角色只有一个字：**断**——master 挂了/需要换主时，**由一个共识复制的单一决策者裁定"谁是新主、从哪个 epoch 继续"**，并把裁决持久化到多数派。这解决的是 4.x 时代的真空：没有可信裁判时，旧主复活会双主写花、或全员等人工；有了 controller，废主不靠 kill 而靠 **epoch fencing**（旧知的上行自然过期失效，见 §7.2），数据面 §4/§5 的复制机制因此只需要"跟主走"，不必自己处理分歧。它不碰消息、不碰路由——**只垄断"切主"这一个动作**。

### 7.1 考证：controller 的共识层是 JRaft 吗（5.5.1 验证）

**是，且两个后端都姓 JRaft**：`controller/impl/` 下并存 `JRaftController`（直用 `jraft-core`，controller/pom L67）与 `DLedgerController`（dledger jar，pom L36——DLedger 本身即 JRaft 团队的上层封装），`ControllerManager` L110 按 `controllerType` 配置二选一。

**但关键在复制对象**：`JRaftControllerStateMachine.onApply → replicasInfoManager.deserializeFrom`（L279）——Raft log 里跑的是**控制面事件**（broker 存活台账、每 brokerSet 的 syncStateSet、主从切换任务、epoch），**不是消息**；消息数据面仍走 `AutoSwitchHAService` 自己的 TCP 复制管道（本篇 §4 的往返）。controller 靠 `BROKER_HEARTBEAT`（ControllerManager L259 注册）感知死亡、做出切换决策，决策日志 Raft 复制到 3/5 节点防脑裂。

三个"共识各站在哪一层"的对照：

| 组件 | 共识复制的对象 | 层次 |
|---|---|---|
| controller（JRaft/DLedger 后端） | 主从拓扑元数据 | 控制面：3/5 节点管全集群 |
| DLedgerCommitLog | **消息本身**（每条过多数派） | 数据面：每组 broker 自组 Raft |
| AutoSwitchHAService | 不用共识——confirmOffset+epoch 台账 | broker 间复制协议，配合 controller 执行 |

**站位理由**：消息进 Raft = 每条付多数派 RTT；把共识挪到控制面，切换是共识防脑裂的，数据复制仍走强度可调的管道——共识各归其位，这也是 DLedgerCommitLog（数据面共识）式微、controller（控制面共识）上位的核心逻辑。

### 7.2 controller 的功能面与实现机制（本轮验证）

一句话定位：**controller 只管"主从切换的决策权"——不碰消息、不碰路由（路由仍在 NameServer），掌管每个 brokerName 的副本台账与状态机。**

**功能清单**（`ControllerRequestProcessor` 注册的 RequestCode 即职责边界，全实证）：

| 请求码 | 功能 |
|---|---|
| `CONTROLLER_GET_METADATA_INFO`（BrokerOuterAPI L1355） | 问 controller 集群谁是 leader，一切交互先找对人 |
| `CONTROLLER_GET_NEXT_BROKER_ID` / `APPLY_BROKER_ID`（L1377/1427，调用方 ReplicasManager L488/523） | **brokerId 动态申领**，加从库免预配 |
| `CONTROLLER_REGISTER_BROKER` | 成员登记（配套 UpdateBrokerAddress/CleanBrokerData 事件） |
| `BROKER_HEARTBEAT`（ControllerManager L259） | 存活台账，broker 单向上报 |
| `CONTROLLER_ELECT_MASTER` | 切主 |
| `CONTROLLER_ALTER_SYNC_STATE_SET` | in-sync 集合变更申报 |
| `GET_REPLICA_INFO` / `GET_SYNC_STATE_DATA`、`CLEAN_BROKER_DATA`、`GET/UPDATE_CONTROLLER_CONFIG` | 运维查询/摘除/配置 |

**机制四流水线**：

1. **一切状态变更 = 事件 → Raft log → apply**。事件全集在 `impl/event/`：`ElectMasterEvent` / `AlterSyncStateSetEvent` / `ApplyBrokerIdEvent` / `UpdateBrokerAddressEvent` / `CleanBrokerDataEvent`（+`EventSerializer`）。写路径样板（DLedgerController L187-190）：`scheduler.appendEvent → replicasInfoManager.electMaster(request, electPolicy)`——串行队列保序 + 提议进共识日志 + `onApply` 落到状态机。状态本体就两张表（ReplicasInfoManager L81-82/107）：`replicaInfoTable`（brokerName→成员台账）、`syncStateSetInfoTable`（brokerName→主地址+epoch+inSyncSet）。
2. **死亡判定 = 心跳超时扫描**（DefaultBrokerHeartbeatManager L59/74-94）：超 `scanNotActiveBrokerInterval` → 出表+关 channel+`notifyBrokerInActive` → listener 链（DLedgerController L285）触发 electMaster。心跳台账有 Default/**Raft 两实现**——Raft 版（RaftBrokerHeartBeatManager）把活表也过 consensus：**controller 自身重启不会瞬间全员误判死亡**。
3. **选举裁决与 epoch fencing**（ReplicasInfoManager L193-268）：首次直选；**老主仍活则拒绝**（防误抖抢选）；选出新主 → `masterEpoch+1`（L50/57）写进响应。废旧主不靠任何 revocation RPC——旧主复活后的上行在状态机三连检查被拒：leader 不是它（L136）、**master epoch 过期**（L145）、syncStateSet epoch 过期（L153）。**单调 epoch 让迟到的旧世界成员自然失效，无需 kill**。
4. **决策回传零主动呼叫**：controller 无任何连 broker 的 client（grep 实证），全部结果**搭车在 broker 上行请求的同步响应**（electMaster 响应带新主+masterEpoch+syncSet；心跳响应同理）。与 §4.2 同构：**身份不定/后动的那头拨号，决策方永不持有对端地址**。

**一个关键分工：controller 不测量 lag**。"谁算 in-sync"由**拥有数据的 master** 按 gap 判定（§2 `isInSyncSlave`）后 `ALTER_SYNC_STATE_SET` **申报**，controller 只做版本校验+共识记账——测量留在有信息的地方，决策留在需要一致的地方；epoch 的**分配权**在 controller，**截断执行**在 broker 数据面（§4.2 末注）。

**边界**：controller 集群全灭不影响消息收发，只失去自动切换（退化人工）。与 ZooKeeper 方案比：共识内建、专职专域；与 Kafka KRaft 比：KRaft controller 连 topic 元数据全家托管且进程内嵌 broker，RocketMQ 是**独立进程 + 只管主从状态机 + NameServer 一行没动**——最小侵入的演进策略。

## 8. 通用设计提炼（本主题收口）

1. **一个 DTO 两种等待**：把「等条件达成」抽象成通用原语 `WaitRequest(target, deadline, ackNums)`——本篇里刷盘等 `flushedWhere`、复制等 slave ack，两处复用同一个 `GroupCommitRequest` 即是示范。
2. **发起 / 执行 / 兜底完成三角色**：写线程发起、GroupCommitService 执行、FlushDiskWatcher 守 deadline 兜底。推论：凡是调用方不阻塞等待的 future，必须有一个独立受托人保证它"最迟在某时刻被完成"——否则执行线程一挂，请求就永久悬挂。
3. **预检给专错码**：`IN_SYNC_REPLICAS_NOT_ENOUGH` 在动手前就告知「条件不满足」并返回专用码，好过让调用方拿一个语义模糊的超时。
4. **节拍模式要有唤醒纪律**：`flushCommitLogTimed=true` 下 `wakeup()` 全部空放——要么用 `waitForRunning`，要么干脆不提供唤醒接口，别留一个不生效的开关。

## 9. 未钻清单（后续候选）

- [ ] `WriteSocketService` 写侧的 mmap 切片生命周期细节（`selectMappedBufferResult.release` 时机、半写续传 `lastWriteOver` 的完整边界）
- [ ] `AutoSwitchHAService` 截断路径：Catchup 状态下 epoch 分叉怎么定位截断点（`EpochFileCache`/`truncatePrefix`）
