# RocketMQ 高性能高吞吐成因分析（5.5.1）

> 主题边界：横向综合篇——不重复链路细节，只回答一个问题：**每条消息的固定成本（syscall、fsync、序列化、RPC 往返、索引构建）去了哪里，被谁摊掉**。纵向解剖见三篇姊妹文档：《rocketmq-发送链路精读》（下称发送篇）、《rocketmq-消费链路精读》（下称消费篇）、《rocketmq-刷盘与复制精读》（下称刷盘篇）。
> 口径：行号对照本机 `~/github/rocketmq`（5.5.1-38-g78b96bc5e）逐行核验；默认值取自本机源码行。与流传甚广的旧文口径冲突处（默认锁类型、零拷贝机制）以本篇实测为准。

---

## 0. 总述：三句话答案

1. **把随机 IO 变成顺序 IO**：所有 topic 混写一个 CommitLog（顺序 append），读侧靠 20 字节定长单元的 ConsumeQueue 小文件定位——写不吃 topic 数量，读不吃消息总量。
2. **把昂贵操作挪出关键路径**：序列化在全局锁外、索引异步派发、刷盘与复制 future 化并行——写请求的临界区只剩一次 memcpy，返回不等任何慢 IO。
3. **把 per-message 固定成本摊进批次**：组提交摊 fsync、攒批发送/批量拉取摊 RPC 往返、长轮询摊空转、线程本地 buffer 摊分配——消息越多，单条成本越低。

高吞吐不是"某个优化很猛"，而是**每一层都在做同一件事：成本摊还**。下面按写路径、读路径、网络与线程模型、背压四面展开，§5 收口成一张成本账。

## 1. 写路径：顺序 append 能快到什么程度

### 1.1 单 CommitLog：用全局一把锁换顺序写

- 所有 topic/queue 的消息混写进**同一个物理文件序列**，1G 一个滚动（`mappedFileSizeCommitLog=1G`，MessageStoreConfig L52）。磁盘磁头/SSD 写放大都只面对一条顺序流。
- **对照 Kafka**：Kafka 每个 partition 一个日志段文件，partition 数少时同样是顺序写；但 partition 数上千后，并发写分散在上千个文件，OS 视角退化为随机写。RocketMQ 把"逻辑分区"（ConsumeQueue）与"物理分区"（CommitLog）解耦——**写吞吐对 topic/queue 数量不敏感**，这是它支撑单机数万 topic 的根因。代价在读侧：物理混写后必须补一层逻辑索引（§2.1）。
- 全局 `putMessageLock` 是**故意保留的**（发送篇 §4.1）：CommitLog 的语义就是全局有序，锁串行换顺序写，顺序即正确性。优化方向不是消灭锁，而是把锁内工作压到极限（§1.3）。

### 1.2 mmap：写 = 一次 memcpy 进 page cache

`DefaultMappedFile.init`（L199）：`RandomAccessFile` + `fileChannel.map(READ_WRITE, 0, fileSize)`（L217）——1G 文件一次性映射进虚拟地址空间。

`appendMessagesInner`（L351）的写入动作：

```java
byteBuffer = appendMessageBuffer().slice();   // mmap 切片，L367
byteBuffer.position(currentPos);              // 定位到当前写位置
result = cb.doAppend(fileFromOffset, byteBuffer, ...);  // 直接写进切片 = 写进 page cache
WROTE_POSITION_UPDATER.addAndGet(this, result.getWroteBytes());  // CAS 推进写位置，L416
```

- **没有 write 系统调用、没有 JVM 堆拷贝**：doAppend 的 memcpy 目的地就是 page cache 本身，脏页由内核异步回写。用户态写路径与磁盘速度彻底解耦。
- 异步刷盘下 `PUT_OK` 的语义 = 进了 page cache（刷盘篇 §3.2），持久性由 OS 兜底、500ms 节拍器收敛。
- **堆内不攒消息**（刷盘篇 §6）：JVM 堆里只有 mappedFiles 链表 + 每文件一个元对象（常数级），物理驻留全在 page cache、由内核 LRU 水位决定。堆积一亿条消息也不会压出 GC 风暴——**这是 Java 写的存储引擎却能对标 C++ 的关键：把内存管理外包给内核**。

### 1.3 两层锁：临界区只剩 memcpy

`CommitLog.asyncPutMessage`（L999）的锁结构是两层的（发送篇 §4.1 只展开了全局锁，这里补全）：

| 锁 | 粒度 | 锁内工作 | 行号 |
|---|---|---|---|
| `topicQueueLock` | 每 topic-queue 一把 | `assignOffset`（分配队列逻辑位点）+ `encode`（序列化） | L1068 / L1077 / L1080 |
| `putMessageLock` | 全局一把 | 设 storeTimestamp + `appendMessage`（memcpy 进 mmap）+ 必要的文件滚动 | L1087-1143 |

- **序列化被挡在全局锁外**：encode 是 CPU 密集操作，不同 queue 的消息在各自 topicQueueLock 下并行编码；全局锁内只剩"改全局写位置 + memcpy"。临界区长度 ≈ 消息字节数 ÷ 内存带宽，微秒级。
- **默认锁口径修正**：`useReentrantLockWhenPutMessage=true`（MessageStoreConfig L166）——**5.5.1 默认是 ReentrantLock，不是自旋锁**。大量旧文"默认 spin lock"是 4.x 早期口径。选择逻辑（CommitLog L150-153）：`useABSLock`（L587，默认 false）→ 自适应退避自旋锁 `AdaptiveBackOffSpinLockImpl`；否则按前者二选一。临界区已压到微秒级后，竞争不激烈时自旋省上下文切换、竞争激烈时 ReentrantLock 省 CPU 空转——5.x 把默认拨向后者，并留了 ABS 锁这个"自适应"第三选项。
- 锁持有时间可观测：`elapsedTimeInLock > 500ms` 打 `[NOTIFYME]`（发送篇 §4.1）；`beginTimeInLock` 还被 `isOSPageCacheBusy` 复用为页缓存失速探针（§4）。

### 1.4 线程本地 direct buffer：编码路径零 per-message 分配

`MessageExtEncoder` 构造时 `byteBuf = alloc.directBuffer(maxMessageSize)`（L55）——**每个发送线程一块堆外编码 buffer，ThreadLocal 持有、反复复用**（CommitLog L143-146）。encode 把消息写进这块 buffer，`msg.setEncodedBuff(...)` 带走引用，锁内 doAppend 只做一次 memcpy。

效果叠加：① 消息编码不产生堆对象，无 per-message GC 压力；② direct buffer 在堆外，doAppend 从堆外到 page cache，全程不经过 JVM 堆；③ buffer 随线程复用，摊掉分配成本。这与 §1.2 的"堆内不攒消息"是同一设计哲学的两端：**JVM 堆只放控制面，数据面全在堆外/内核**。

### 1.5 文件预分配 + 预热：把创建抖动挪出写路径

写满 1G 滚新文件时，`MappedFileQueue.getLastMappedFile`（L379）不自己 new，而是委托 `AllocateMappedFileService.putRequestAndReturnMappedFile`（L63）：

- **一次预提交两个文件**：next + nextNext（L81-106）塞进 `requestQueue`，后台单线程 `mmapOperation`（L173）异步创建。写线程最多等"下一个"文件就绪，"下下个"永远在后台先建好——**文件创建的毫秒级抖动被流水线吞掉**。
- **预热**（`warmMapedFileEnable` 默认 false，Config L265；开启后 mmapOperation L216-224 调用）：`warmMappedFile`（DefaultMappedFile L797）对 1G 文件**逐 4K 页写一个 0**——预触发缺页中断、预分配物理页、预建页表；SYNC_FLUSH 时每 `flushLeastPagesWhenWarmMapedFile` 页 force 一次。收尾 `mlock`（L835 → L931，JNA 调 LibC）把映射页**锁在内存**，防 swap、防内核回收。
- why：mmap 只是建立虚拟映射，首次写每页仍会缺页中断 + 磁盘块分配；1G 文件 = 26 万次缺页。预热把这笔成本一次性付在后台，写路径从此每页都是"热"的。mlock 则保证热页不会被内存压力偷走——**预热解决冷启动，mlock 解决保持热**。

### 1.6 刷盘与复制：摊还 + 并行（概要，详见刷盘篇）

append 之后的两个慢 IO 这样处理：

- **组提交摊 fsync**（刷盘篇 §3.1）：offset 全局单调，刷到批内最大 nextOffset 的**一次 fsync 顺带满足全部在前请求**——fsync 成本被一批请求分摊，这是 SYNC_FLUSH 仍能高吞吐的根因。
- **刷盘 ∥ 复制**（刷盘篇 §1）：`handleDiskFlush` 与 `handleHA` 两个 CompletableFuture 并行 fork、`thenCombine` 汇合，总耗时 = max 而非相加。
- **默认不等盘**：ASYNC_FLUSH + ASYNC_MASTER 是发行版默认，写请求 append 完即返回，500ms 节拍器后台收敛（刷盘篇 §3.2）。
- **transientStorePool**（默认关，刷盘篇 §3.3）：堆外池写 → commit 进 page cache → flush 落盘三段接力，把"写"与"刷"进一步分到两块内存，读写分离的可选强化。

## 2. 读路径：索引与"零拷贝"的真相

### 2.1 ConsumeQueue：20 字节定长单元，寻址 = 数组下标

- CQ 单元固定 20 字节（physicOffset 8B + msgSize 4B + tagsCode 8B，发送篇 §6.3），文件 300000×20B ≈ 5.72MB（Config L137）。`queueOffset × 20` 直接定位——**逻辑队列读就是一次小文件顺序扫 + 一次数组式寻址**，无解析、无搜索。
- 拿到 physicOffset 后回 CommitLog 取消息体：`selectMappedBufferResult` 返回 **mmap 切片引用**而非拷贝（发送篇 §6.5）——读路径同样不经过 JVM 堆（默认传输路径除外，见 §2.2）。
- **索引异步构建**：`ReputMessageService`（发送篇 §4.3）事后从 CommitLog 顺序读、派发写 CQ/Index——写路径不等索引，否则顺序写被随机写拖垮。
- **衍生物不配持久性承诺**（刷盘篇 §5）：CQ 自有 1s 节拍刷盘、不走 HA 复制、丢了从 checkpoint 重放重建。原文（CommitLog）强一致，派生状态随时可重算——**把持久性成本集中付给唯一原文，是存储层最大的一笔省钱**。

### 2.2 "零拷贝"真相：默认走堆拷贝，FileRegion 也不是 sendfile

两个与流传口径不符的事实（消费篇 §3.3 已验其一，这里补全其二）：

1. **默认 `transferMsgByHeap=true`**（BrokerConfig L134）：pull 回包先把 mmap 切片 read 进堆 byte[] 再 setBody——一次堆拷贝换实现简单与引用计数安全。"RocketMQ 消费默认零拷贝"不成立。
2. **关掉后走的 `ManyMessageTransfer`（FileRegion）也不是 sendfile(2)**：其 `transferTo`（L66-82）实现是 `target.write(mmap 切片 ByteBuffer)`——把 page cache 切片直接写给 socket channel，**不是** `FileChannel.transferTo` 那条 sendfile 系统调用路径。数据仍经历一次内核内拷贝（page cache → socket buffer），但**全程不进 JVM 堆**：无 byte[] 分配、无堆拷贝、无 GC 压力。

准确表述：**RocketMQ 的"零拷贝"= mmap 切片直写、零 JVM 堆拷贝**；sendfile 是 Kafka 的路线（其日志段是纯文件、无混写索引回指需求）。RocketMQ 选 mmap 而非 sendfile 有结构性原因：消息体在 CommitLog 里按物理偏移散落，一次 pull 要聚合多个不连续区段（`GetMessageResult.getMessageBufferList()`），mmap 切片集合可以统一包装成一个 FileRegion 逐段写，sendfile 反而要多次系统调用。

- 零拷贝的前提是生命周期管理：`writeAndFlush(fileRegion)` 的 **listener 里才 `getMessageResult.release()`**（DefaultPullMessageResultHandler L154-171）——异步写完之前切片必须活着，引用计数是 mmap 直写的配套安全带。

### 2.3 长轮询 + 批量拉取：摊 RPC 往返与空转

- **长轮询**（消费篇 §3.4）：无消息时请求挂起在 `PullRequestHoldService`，新消息到达由 reput 派发事件即时唤醒，5s 扫描兜底。对比短轮询：空拉 RPC 往返被摊成零；对比纯 push：broker 无需维护推送速率与消费者状态。**延迟接近 push，成本接近 pull**。
- **批量拉取**：`pullBatchSize=32`（DefaultMQPushConsumer L237）——一次 pull 最多带 32 条消息，RPC 往返、长轮询挂起、位点提交全被 32 条分摊。注意 `consumeMessageBatchMaxSize=1`（L232）是另一个独立旋钮：**拉取攒批摊网络成本，消费不攒批保单条延迟**，两者解耦。
- 拉取间隔 `pullInterval=0`（L227）：回调续装泵自我驱动（消费篇 §2.4），无节拍空转，消费速度自适应追平生产速度。

## 3. 网络与线程模型：线程数与并发数解耦

### 3.1 全链路异步：没有线程在等慢 IO

三层拼出（发送篇 §2.4/§3.4、刷盘篇 §1）：client"同步 = 异步 + get"→ broker"return null 释放线程"→ store"刷盘复制全 future 化"。任何一层都不阻塞等待下一层——**线程只在有 CPU 活或就绪 IO 时被占用**，这是小线程池撑住高并发的根因，也是 §3.2 线程数差异的前提。

### 3.2 线程池配额的哲学：写少读多

| 池 | 默认值 | 源码 | why |
|---|---|---|---|
| sendMessageThreadPoolNums | `min(CPU, 4)` | BrokerConfig L69 | 写被全局 putMessageLock 串行化，多线程只增加锁竞争与上下文切换，4 个足够喂饱锁 |
| pullMessageThreadPoolNums | `16 + CPU×2` | BrokerConfig L71 | 读是 IO 密集（CQ 寻址 + 切片 + 网络写），无全局锁，可充分并行 |
| Netty serverSelectorThreads | 3 | NettyServerConfig L29 | IO 线程只做读写帧，解码都挪去独立 EventExecutorGroup（发送篇 §2.3） |
| HA 数据面 | 独立裸 NIO 线程 | 刷盘篇 §4 | 主从复制流量不占业务 event loop 一个核 |

**分池本身就是读写隔离**：send 与 pull 不只是线程数不同，而是 `BrokerController` 里两个互不相通的 `ThreadPoolExecutor`——`sendMessageExecutor`（L276/566）与 `pullMessageExecutor`（L277/574）；`registerProcessor` 按 RequestCode 分流，`SEND_MESSAGE` 挂写池（L1175）、`PULL_MESSAGE` 挂读池（L1188）。于是写侧被 `putMessageLock` 拖慢、`sendThreadPoolQueue` 积压时，占满的是自己的池，**饿不到 pull 的线程**。配额（上表）管每侧并发上限，分池管饥饿不互相传染——两件事正交。这也是 §4 快速失败能只清 `sendThreadPoolQueue`（`[PCBUSY_CLEAN_QUEUE]`）而不牵连读的结构前提：若读写同池（如共享派发池），一侧打满即双向拖累，就没有这种隔离。

**写线程少不是缺陷而是结论**：临界区微秒级（§1.3）时，一把锁的串行吞吐 ≈ 单核 memcpy 带宽，远超磁盘与网卡瓶颈——加线程无收益。读线程多是因为读路径天然可并行且耗时在 IO。配额差异本身就是"写串行、读并行"架构的指纹。

### 3.3 客户端的成本摊还

- **攒批发送**：`autoBatch`（默认 false，DefaultMQProducer L160）开启后多条消息攒进一个 RPC；`canBatch`（L434）排除延迟消息、重试消息、超尺寸消息。只有无 timeout 的 `send(Message)` 走攒批路径（发送篇 §1.1）——攒批以延迟换吞吐，所以默认关、由用户按场景开。
- **压缩**：body > 4KB 自动压缩（`compressMsgBodyOverHowmuch=4096`，L126）——用客户端 CPU 换网络带宽与 broker 存储，大消息场景的吞吐杠杆。
- **消费端流控**：`pullThresholdForQueue=1000`（L185）——本地 ProcessQueue 缓存水位闸门（消费篇 §2.2），拉取速度超过消费速度时暂停拉取。**高吞吐系统必须自带"消费者跟不上"的退路**，否则 broker 零拷贝再快也只是把压力搬进消费者堆里。

## 4. 高吞吐的另一面：背压与快速失败

高吞吐 ≠ 无限接单。page cache 失速会传染——一次慢回写拖住所有写线程，排队请求越积越多。RocketMQ 的对策是三层防护 + 快速失败：

1. **入口拒**：`SendMessageProcessor` L135——`isOSPageCacheBusy() || isTransientStorePoolDeficient()` → 直接回 `OS_PAGE_CACHE_BUSY`。探针本身（DefaultMessageStore L742-748）：`beginTimeInLock` 距今 > `osPageCacheBusyTimeOutMills=1000ms`（Config L270）即判忙——**用"全局锁被持有时长"反推 page cache 是否失速**（memcpy 进 mmap 本该微秒级，持锁超秒只能是缺页/回写卡住）；`diff < 10000000` 的上界是排除未持锁时 `beginTimeInLock=0` 的哨兵值。
2. **清队列**：`BrokerFastFailure`（L91-110）每 10ms 巡检，page cache busy 期间直接从 sendThreadPoolQueue 头部 poll 出请求回 `SYSTEM_BUSY`（`[PCBUSY_CLEAN_QUEUE]`）——**宁可立刻失败触发客户端重试/故障规避（MQFaultStrategy，发送篇 §1.3），不让请求在队列里等到超时**。失败一个请求的成本远低于拖死整条流水线。
3. **磁盘水位**：删除阶梯 75% 提前删 / 85% 强制删 / 90% 拒写（刷盘篇 §6）——堆积的去处是磁盘文件数，水位闸门保证磁盘不被写满。

加上 broker 入口的 `rejectRequest()`（发送篇 §3.2）与消费端 pullThreshold（§3.3），**每一层都有自己的过载闸门，且都选择"早拒/早失败"而非"排队等死"**——这是高吞吐系统在异常工况下不雪崩的共性设计。

## 5. 收口：一张成本账

| 固定成本 | 摊还/规避机制 | 源码锚点 |
|---|---|---|
| 随机写磁盘 | 单 CommitLog 顺序 append，topic 数无关 | CommitLog L999 / Config L52 |
| write syscall + 堆拷贝 | mmap 切片 memcpy 直达 page cache | DefaultMappedFile L217/L351 |
| 锁竞争 | 两层锁：encode 锁外并行，锁内只 memcpy | CommitLog L1068/L1087 |
| per-message 分配/GC | 线程本地 direct buffer 复用 | MessageExtEncoder L55 |
| 文件创建/缺页抖动 | 预分配 next+nextNext + 逐页预热 + mlock | AllocateMappedFileService L63/L216 / DefaultMappedFile L797 |
| fsync | 组提交：一次 fsync 服务一批请求 | 刷盘篇 §3.1 |
| 刷盘 + 复制串行等待 | CompletableFuture 并行，耗时 = max | 刷盘篇 §1 |
| 索引随机写 | ReputMessageService 异步派发；CQ 可重放不复制 | 发送篇 §4.3 / 刷盘篇 §5 |
| 消费寻址 | CQ 20B 定长单元，offset×20 数组式定位 | Config L137 |
| 消费堆拷贝 | mmap 切片直写（transferMsgByHeap=false 可选） | ManyMessageTransfer L66 |
| RPC 往返/空转 | 攒批发送 + pullBatchSize=32 + 长轮询 | DefaultMQProducer L160 / PushConsumer L237 |
| 线程等待慢 IO | 全链路异步，线程数与并发数解耦 | 发送篇 §2.4/§3.4 |
| 写侧打满饿死读 | send/pull 两个独立池，按 RequestCode 分派，饥饿不跨侧传染 | BrokerController L276/566 · L277/574 · L1175/1188 |
| 过载雪崩 | 页缓存探针 + 快速失败 + 三级水位 | SendMessageProcessor L135 / BrokerFastFailure L93 |

**一句话收口**：RocketMQ 的高性能不是单点魔法，而是"顺序写 + page cache 外包内存管理 + 临界区极小化 + 一切慢操作异步化 + 一切固定成本批量化"五件事在每一层重复执行；高吞吐的可持续性则由"每层自带闸门、早拒早失败"兜底。

### 验收问题（读完必须能答）

- [ ] 为什么 RocketMQ 写吞吐对 topic 数量不敏感，而 Kafka partition 多了会退化？代价转移到了哪里？
- [ ] 默认配置下 `putMessageLock` 是哪种锁？锁内具体做哪几件事？为什么 encode 必须在锁外？
- [ ] mmap 之后首次写每页仍会发生什么？预热和 mlock 各解决哪一半问题？
- [ ] 5.5.1 pull 回包默认拷贝几次、经过 JVM 堆吗？关掉 transferMsgByHeap 后是 sendfile 吗？准确表述是什么？
- [ ] 为什么发送线程默认只有 min(CPU,4) 而拉取线程 16+CPU×2？这个差异反推出什么架构事实？
- [ ] `isOSPageCacheBusy` 用什么信号判忙？为什么 BrokerFastFailure 选择清队列回 SYSTEM_BUSY 而不是让请求排队？
- [ ] CQ 为什么"不配"同步刷盘与 HA 复制？丢了怎么办？