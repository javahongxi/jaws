# RocketMQ 发送链路精读：client → store（5.5.1）

> 主线：一条 `DefaultMQProducer.send(Message)` 如何从客户端一路走到 Broker 落盘。
> 口径：全部结论对照本机 `~/github/rocketmq`（git describe `rocketmq-all-5.5.1-38-g78b96bc5e`）逐行核验；
> 行号取本机版本，仓库演进会漂移，只认结构不认个位行号。定性：个人学习笔记。

阅读顺序（自顶向下，即一次同步发送的调用栈）：

```
① client   DefaultMQProducer.send
            └─ DefaultMQProducerImpl.sendDefaultImpl  （路由 / 选队列 / 重试预算）
                 └─ sendKernelImpl                    （压缩 / 组 RequestHeader / 通信模式分叉）
                      └─ MQClientAPIImpl.sendMessage   ── 交给 ↓
② remoting  RemotingCommand 编码 + NettyRemotingClient 发帧   ── TCP ──►
③ broker    NettyServer → ProcessorManager → SendMessageProcessor.processRequest
                 └─ 参数解码 / 权限 / Hook → 构造 AppendMessageCallback
                      └─ MessageStore.putMessage  ── 交给 ↓
④ store     CommitLog.putMessage → MappedFile append/flush
                 └─ ReputMessageService 异步派发 → ConsumeQueue + IndexFile
```

---

## ① client：发送入口与容错决策层

**这一层的职责不是"把字节发出去"，而是"决定把这条消息发给谁、发几次、超时怎么算"**——真正的网络编解码在 ②。所以 client 层三件事：路由查询、队列选择、重试与超时预算。

### 1.1 入口有一个易忽略的分叉：只有无 timeout 的 `send(Message)` 走攒批

`producer/DefaultMQProducer.java`：

- L470 `send(Message msg)`：`msg.setTopic(withNamespace(...))` 后，**判断 `getAutoBatch()`**（L473）：
  - 开 → `sendByAccumulator(msg, null, null)`（L474）——5.x 新增的**客户端攒批**，走 `ProduceAccumulator`（producer 包里，本层先不钻）；
  - 关 → `sendDirect(msg, null, null)`（L476）。
- L493 `send(Message msg, long timeout)`：**直接** `defaultMQProducerImpl.send(msg, timeout)`（L496），**不判 autoBatch**。

> **取舍 why**：带显式 timeout 的发送语义上要求"这次调用在 N ms 内出结果"，与"攒一批等窗口"冲突，所以攒批只挂在 `autoBatch` 开关的无参路径上。**读代码时别想当然以为所有 send 都会攒批**——这是 5.x 相对 4.x 新增分叉，最容易踩空的地方。

### 1.2 `sendDefaultImpl`（Impl L738）：路由 + 重试循环 + 超时预算

关键结论（逐条对源码）：

1. **前置校验**：`makeSureStateOK()`（L744，生产者必须 RUNNING）+ `Validators.checkMessage`（L745，topic 合法、body 非空且不超限）。
2. **路由来源**：`tryToFindTopicPublishInfo(topic)`（L750，方法 L894）——先查本地 `topicPublishInfoTable` 缓存，miss 才 `updateTopicRouteInfoFromNameServer` 拉。**路由是客户端本地缓存 + 后台定时刷**，发送热路径不查 NameServer。
3. **重试次数只对同步生效**：`timesTotal = SYNC ? 1 + retryTimesWhenSendFailed : 1`（L756）。异步/单向不在此循环重试（异步的重试下沉到 `MQClientAPIImpl.sendMessage` 的 `retryTimesWhenSendAsyncFailed`，见 1.4）。
4. **超时预算逐次扣减**：`curTimeout = timeout - costTime`（L780）；且**只要还有下一次重试**（`canRetryAgain`）就把单次超时压到 `sendMsgMaxTimeoutPerRequest` 上限（L786）。**why**：防止第一次就把总预算耗光、后面的 broker 没机会试。
5. **成功后的分支**（L792 switch）：ASYNC / ONEWAY 直接返回 null（结果走回调/不管）；SYNC 若 `sendStatus != SEND_OK`（例如落盘成功但从节点同步失败）且开了 `retryAnotherBrokerWhenNotStoreOK` 则 `continue` 换 broker（L798-802）。
6. **异常分类驱动不同的容错动作**：
   - `RemotingException`（网络层）→ `updateFaultItem(..., isolation=true, ...)`（L821）：把该 broker 拉黑一段时间；
   - `MQBrokerException` → **只有响应码在 `retryResponseCodes` 白名单里才 `continue` 重试**（L836），否则抛出。why：像"主 broker 写盘超时"可重试，像"topic 权限不足"重试无意义。
7. **全部失败**：拼 `brokersSent[]` 轨迹抛 `MQClientException`（L862-885），并按异常子类回填 responseCode（连不上 / 超时 / broker 不存在分别给不同码）。

### 1.3 选队列：`MQFaultStrategy`（client/latency，L143）是负载均衡 + 故障规避的合体

`selectOneMessageQueue`（Impl L720 直接委托）。两条主路径：

- **`sendLatencyFaultEnable=true`**（默认 false，L146）：三级降级 `availableFilter → reachableFilter → 纯轮询`（L150-160）。`availableFilter` = 延迟低且未拉黑；`reachableFilter` = 只要还可达即可。重试时 `resetIndex`（L147）把轮询指针复位，配合 `brokerFilter` 避开上一个 broker。
- **关闭（默认）**：`tpInfo.selectOneMessageQueue(brokerFilter)`（L163）纯轮询 + 重试避开 lastBrokerName。
- **拉黑时长查表**：`computeNotAvailableDuration`（L178）用两条平行数组 `latencyMax={50,100,550,1800,3000,5000,15000}` 与 `notAvailableDuration={0,0,2000,5000,6000,10000,30000}`（L30-31）做阶梯映射——延迟越高、拉黑越久。且异常拉黑走 `isolation ? 10000 : currentLatency`（L173），即网络异常至少按 10s 档处理。

### 1.4 `sendKernelImpl`（Impl L911）：真正把消息"定型"并发往 remoting

1. **解析 broker 地址**：`findBrokerAddressInPublish(brokerName)`（L919），miss 再拉一次路由（L921）；`brokerVIPChannel`（L928）按端口 +2 走 VIP 通道（省一次队列号选择，老机制，默认关）。
2. **压缩**：`tryToCompressMessage(msg)`（L945）——超阈值压缩后置 `COMPRESSED_FLAG` + 压缩算法 flag（L946-947）。压缩会改 body，注意 L1095 finally 里 `msg.setBody(prevBody)` **把用户对象还原**，避免副作用；异步路径因要跨线程，L1030 先 `cloneMessage` 再还原原对象。
3. **组装 `SendMessageRequestHeader`**（L993-1006）：producerGroup / topic / queueId / sysFlag / bornTimestamp / flag / **properties 经 `MessageDecoder.messageProperties2String` 拼成字符串**（L1002）/ batch 标志 / brokerName。**这是 client→broker 的"业务契约对象"**，②层会把它塞进 RemotingCommand 的 header。
4. **通信模式三叉**（L1022）：ASYNC 走带 callback 的 sendMessage（L1047），SYNC/ONEWAY 走不带 callback 的（L1067）。**均调 `MQClientAPIImpl.sendMessage(...)`** —— client 层到此为止，下一站进 remoting。

### 验收问题（读完 ① 必须能答）

- [ ] 为什么 `send(msg, timeout)` 不触发 `autoBatch` 攒批？如果用户既设 autoBatch 又用带 timeout 的 send，实际会攒批吗？
- [ ] 同步发送第一次成功但 `sendStatus = FLUSH_DISK_TIMEOUT`，在什么配置下会换 broker 重发？默认会吗？（抓 L798 与 `retryAnotherBrokerWhenNotStoreOK`）
- [ ] `sendLatencyFaultEnable` 关掉时，重试选队列靠什么避免反复撞同一个坏 broker？（抓 `brokerFilter` + `lastBrokerName` + `resetIndex`）
- [ ] 一次网络 `RemotingException` 会让该 broker 被拉黑多久？走的是 `currentLatency` 还是别的？（抓 L173 `isolation ? 10000`）
- [ ] 压缩后的 msg 为什么要在 finally 里还原 body？不还原会怎样？（抓 L1095 与异步 clone L1030）

---

## ② remoting：编码与传输

**这一层的职责：把 client 交来的 `SendMessageRequestHeader` + body，编成一条 TCP 帧发出去，并把"同步等待"实现为"异步机制 + 阻塞"。**

### 2.1 交棒点：`MQClientAPIImpl.sendMessage`（L534）造 `RemotingCommand`

- L549-566：`RemotingCommand.createRequestCommand(code, requestHeader)`。**code 的选择有讲究**：
  - 普通 → `SEND_MESSAGE`（L564）
  - `sendSmartMsg || msg instanceof MessageBatch` → 转 **V2** 头 `SEND_MESSAGE_V2`（L562），批量再单独走 `SEND_BATCH_MESSAGE`；
  - 回复消息（RPC）→ `SEND_REPLY_MESSAGE(_V2)`（L555/557）。
- **V2 的"smart"是什么**：`SendMessageRequestHeaderV2` 把字段名换成**单字母** `a/b/c/…/n`（`@JSONField`，源码 L41-69，`a=producerGroup, b=topic, i=properties, n=brokerName`）。**why**：header 走 JSON 序列化时字段名占比可观，短名直接省 header 字节；`createSendMessageRequestHeaderV2` 做字段搬运。
- **body 不进 header**：`request.setBody(msg.getBody())`（L567）——消息体单独放 body 段，header 只放元数据。这是"header/body 分离"的物理边界。

### 2.2 帧的物理布局（`RemotingCommand.encodeHeader` L483 / `decode` L191）

一条帧 = 

```
| 4B 总长 length | 4B(高1字节=序列化协议类型 | 低3字节=header长度) | header data | body data |
```

- 写：`result.putInt(length)`（L499，length=4 + headerLen + bodyLen）；`putInt(markProtocolType(headerLen, serializeType))`（L502）。
- **协议类型位打包**：`markProtocolType = (type.getCode() << 24) | (source & 0x00FFFFFF)`（L248-249）。读回：`getHeaderLength = length & 0xFFFFFF`（L213）、`getProtocolType = (source >> 24) & 0xFF`（L236）。**why 借一个字节当类型标签**：同一端口要兼容两种 header 编码，用总长字段的高位复用作类型枚举，不额外加字段。
- **header 两种序列化**（`headerDecode` L216）：`JSON`（`RemotingSerializable.decode`）与 `ROCKETMQ`（`RocketMQSerializable.rocketMQProtocolDecode`，自研紧凑二进制）。body 长度 = `总长 - 4 - headerLen`（L201）。

### 2.3 Netty pipeline 与零拷贝写帧

- Client/Server 的 pipeline 都挂 `new NettyEncoder()` + `new NettyDecoder()`（`NettyRemotingClient` L220-221、`NettyRemotingServer` L299-301，且**解码跑在独立 defaultEventExecutorGroup**，见 §③）。
- `NettyEncoder extends MessageToByteEncoder<RemotingCommand>`：`fastEncodeHeader(out)`（L37）**直接写进出站 ByteBuf**，避开 `encodeHeader()` 返回中间 ByteBuffer 的一次拷贝分配；随后 `out.writeBytes(body)`（L40）。
- `NettyDecoder extends LengthFieldBasedFrameDecoder`（L29）：先按长度字段切出整帧，再 `RemotingCommand.decode(frame)`（L48）。**这是解决 TCP 粘包/半包的落点**。

### 2.4 同步发送 = 异步 + 阻塞（架构点）

`invokeSyncImpl`（`NettyRemotingAbstract` L583）**不是另一套同步实现**，而是：

```java
invokeImpl(...).thenApply(ResponseFuture::getResponseCommand).get(timeoutMillis, ...); // L587-588
```

底层 `invoke0`（L601）统一走异步：
- `opaque = request.getOpaque()`（L605，由 `createNewRequestId()` 单调自增，`MQClientAPIImpl` L728 处赋值）——**opaque 是请求/响应关联的 key**；
- `semaphoreAsync` 限流在途请求（L609，防打爆）；
- `new ResponseFuture(channel, opaque, request, timeout, callback, once)` 存入 `responseTable.put(opaque, responseFuture)`（L624-642）；
- 响应回来时 `processResponseCommand` 按 `opaque` 从 `responseTable.remove(opaque)`（L470/697）取回 future、回填结果并唤醒等待者。
- 后台扫描线程清理超时未回的进行中请求（L565 附近 `cleanExpiredRequest`）。

> **架构点**：**同步只是异步 future 上盖了个 `.get()`**——所以"全链路异步"在 remoting 层已见雏形，越往下（store 的 flush 回调）越彻底。

### 验收问题（读完 ② 必须能答）

- [ ] 为什么 V2 要把字段改成单字母？省的是哪一段的字节？（header JSON，非 body）
- [ ] `length` 字段的高 1 字节干嘛用？为什么要复用它而不是加一个独立字节？（序列化类型标签，兼容 JSON/ROCKETMQ 两套 header）
- [ ] body 长度在 decode 时怎么算出来，header/body 的分界靠什么？（总长-4-headerLen）
- [ ] 粘包/半包在哪一层解决？（NettyDecoder extends LengthFieldBasedFrameDecoder）
- [ ] 同步发送超时是 `invokeSyncImpl` 的 `.get(timeout)` 抛，还是 ResponseFuture 扫描线程抛？两者会不会都触发、谁先到？（抓 L588 与 cleanExpiredRequest）
- [ ] opaque 从哪来、放哪张表、什么时候被移除？（createNewRequestId / responseTable / processResponse 或超时扫描）

## ③ broker：接收与分发

**这一层的职责：从 Netty event loop 手里接过解码好的 `RemotingCommand`，按 code 派到业务线程池，做完校验/映射/流控后把消息交给 store，再把结果回写响应。**

### 3.1 code → processor → executor 的三元注册（`BrokerController.registerProcessor` L1175-1183）

```
SEND_MESSAGE / SEND_MESSAGE_V2 / SEND_BATCH_MESSAGE / CONSUMER_SEND_MSG_BACK
        └─► sendMessageProcessor，绑定线程池 sendMessageExecutor
```

- 普通 `remotingServer` 与 `fastRemotingServer`（VIP 快速通道）**都注册**同一批（L1175 vs L1180）——两套端口/线程池隔离不同请求。
- **两级线程模型**：① 解码在 `defaultEventExecutorGroup`（§2.3，Netty 侧，把 CPU 型解码从 IO 线程剥离）；② `processRequest` 在 `sendMessageExecutor`（业务型）。**IO 线程全程不碰业务**。

### 3.2 过载保护：`rejectRequest()`（L129-140）

派发前 Netty 层会问 processor "要不要拒"：

- `BrokerRole.SLAVE` 且未开 `enableSlaveActingMaster` → 拒（从节点不接写，L131-133）；
- `isOSPageCacheBusy()` 或 `isTransientStorePoolDeficient()` → 拒（L135）——**落盘跟不上时用拒绝换取不 OOM/不雪崩**，异步刷盘 + transient store pool 的背压出口。

### 3.3 `processRequest`（L89）主干

1. `parseRequestHeader`（L96）反出 `SendMessageRequestHeader`；
2. **静态 topic（TopicQueueMapping，容器化/迁移场景）** 上下文构建 + `rewriteRequestForStaticTopic`（L100-104）——把 client 传的 queueId 重映射到物理队列；普通 topic 直接透传；
3. `buildMsgContext` + `executeSendMessageHookBefore`（L105-107，可插拔鉴权/流控 Hook，抛 `AbortProcessException` 即拒绝）；
4. `clearReservedProperties`（L115）清掉客户端不该带的保留属性（如 POP_CK）；
5. batch / 非 batch 分叉到 `sendBatchMessage` / `sendMessage`（L117-123）。

### 3.4 交给 store：同步 vs 异步两条腿（`sendMessage` 内，L341-379）

`isAsyncSendEnable()` 决定：

- **异步（L341-367）**：`messageStore.asyncPutMessage(msgInner)` 返回 `CompletableFuture<PutMessageResult>` → `.thenAcceptAsync(handlePutMessageResult..., putMessageFutureExecutor)` → **`return null` 立刻释放 sendMessageExecutor 线程**（L367 注释原话 "release the send message thread"）。结果回包被搬到**第三个线程池 `putMessageFutureExecutor`**。
- **同步（L368-379）**：`messageStore.putMessage(msgInner)` **阻塞占着 sendMessageExecutor** 直到落盘返回，再 `handlePutMessageResult`。

> **关键架构点**：broker 侧的"全链路异步"与 §2.4 客户端"同步=异步+get"呼应——`asyncPutMessage` 把"写 CommitLog + 等 flush"做成 future，**发送线程不被磁盘 IO 拖住**，高并发下用固定 worker 数扛更多在途请求。代价：多一次线程池 hop（sendMessageExecutor → putMessageFutureExecutor）+ 回包路径变复杂。这是 RocketMQ 高吞吐的地基之一，务必吃透。

- `handlePutMessageResult`：把 `PutMessageResult` 翻译成响应码，`doResponse` 回写；实际可能出现的落盘态是三种（PUT_OK / FLUSH_DISK_TIMEOUT / FLUSH_SLAVE_TIMEOUT），映射 switch 里虽保留 `SLAVE_NOT_AVAILABLE` 的 case，但 5.5 常规路径已不产出（见 §4.4）。**这些状态正是客户端 §1.2 `retryAnotherBrokerWhenNotStoreOK` 判断的上游来源**。

### 验收问题（读完 ③ 必须能答）

- [ ] 一次 SEND_MESSAGE 从网卡到 processRequest 跨越了几种线程？各干什么？（IO event loop → defaultEventExecutor 解码 → sendMessageExecutor 业务 [→ putMessageFutureExecutor 回包]）
- [ ] `rejectRequest` 在什么负载下会把写请求挡在门外？为什么从节点默认拒写？
- [ ] `isAsyncSendEnable=true` 时，`processRequest` 为什么能 `return null`？谁负责最终回包？
- [ ] `PutMessageStatus.FLUSH_DISK_TIMEOUT` 从 store 产生到让客户端重试，经过哪些点？串起 §1.2、§3.4。
- [ ] 静态 topic 的 `rewriteRequestForStaticTopic` 解决什么问题？（容器/迁移下逻辑队列↔物理队列映射）

## ④ store：CommitLog 落盘与派发（复杂度主战场）

**这一层的设计母题：所有 topic 混写进一个全局有序的 CommitLog（顺序写），读路径（ConsumeQueue / Index）由一个异步派发线程事后构建。** 写要快、要顺序，读要"最终一致地补齐索引"——两者用 `ReputMessageService` 解耦。

入口：`DefaultMessageStore.asyncPutMessage`（L645）/ `putMessage`（L716）→ `CommitLog.asyncPutMessage`（L999）。

### 4.1 `CommitLog.asyncPutMessage`（L999）：把昂贵操作挪到锁外，锁内只做顺序 append

1. **编码在锁外**（L1080-1084）：`putMessageThreadLocal.getEncoder().encode(msg)` 把消息编码进**线程本地 buffer**，`msg.setEncodedBuff(...)`。**why**：编码是 CPU 开销，放锁外并行；临界区只留"改全局写位置 + memcpy 进 mmap"，缩短锁持有时间——这是 CommitLog 高吞吐的第一关键。
2. **全局单锁串行写**（L1087 `putMessageLock.lock()`，spin 或 ReentrantLock 按配置）：
   - `mappedFile.isFull()` → `mappedFileQueue.getLastMappedFile(0)` 滚动新文件（L1098-1099）；
   - `mappedFile.appendMessage(msg, appendMessageCallback, ctx)`（L1109）——真正写入；
   - **`END_OF_FILE`**：当前文件剩余 < `msgLen+8` 时，由**回调**（`DefaultAppendMessageCallback` L2053-2068）写 8 字节 `[maxBlank | BLANK_MAGIC]` 占住尾部并返回此状态；本节（L1114-1131）只是消费方——换下一个文件重试 append。读侧遇 BLANK 返回 `(size=0, success)`（L499-500），`rollNextFile` 跳过。
   - `finally` 解锁，`elapsedTimeInLock > 500ms` 打 `[NOTIFYME]` 日志（L1155，锁竞争可观测）。
3. **成功后** `increaseOffset`（L1147）推进队列逻辑 offset。
4. **flush + 复制异步化**：`handleDiskFlushAndHA`（L1169/1360）→ `handleDiskFlush`（委托 `FlushManager` L1382）+ `handleHA`（同步复制），组合成 `CompletableFuture<PutMessageStatus>` 返回。**三种落盘态**（PUT_OK / FLUSH_DISK_TIMEOUT / FLUSH_SLAVE_TIMEOUT）就在这里产出，逐层回传到 §3.4 → §1.2；`SLAVE_NOT_AVAILABLE` 在 5.5 常规路径已不产生（见 §4.4 概要）。

> **取舍要点**：`putMessageLock` 是"故意保留一把全局锁"——因为 CommitLog 的语义就是**全局有序**，用锁串行换顺序写，**顺序即正确性**。

### 4.2 MappedFile 在 5.5.1 被抽象成接口（`logfile/`）

- `MappedFile` 现为接口（`flush(int)` L154 / `commit(int)` L162），`logfile/` 下有 **WriteFile**（纯 append + FileChannel）与 **MMapFile**（`mappedByteBuffer`）两种实现。这是 5.x 为支持"非 mmap 的 RocksDB 化存储/tieredstore"铺的抽象层（相对 4.x 的重要演进）。
- **TransientStorePool 两段式**（配 §3.2 的背压）：堆外池 DirectByteBuffer 写 → `commit` 进 pagecache → `flush` 落盘。异步刷盘下，写请求 commit 后即可返回，flush 由后台 `GroupCommitService` / `FlushManager` 攒批。

### 4.3 `ReputMessageService`（L2655）：读写解耦的关键线程

`doReput()`（L2711）自转循环：

- `commitLog.getData(reputFromOffset)`（L2723）从 CommitLog 顺序读一段；
- 循环 `checkMessageAndReturnSize(...)`（L2734）解析出一条消息的 `DispatchRequest`（含 topic/queueId/physicOffset/size/tagsCode/keys...）；
- `doDispatch(dispatchRequest)`（L2745）→ 逐个已注册 dispatcher：`ConsumeQueue`（写 20 字节单元：physicOffset|msgSize|tagsCode）、IndexFile、事务索引等；
- `reputFromOffset += size` 推进；`rollNextFile`（L2762）跨文件；
- `behind()`（L2690）= confirmOffset − reputFromOffset，**派发落后量**，是"写完成但索引未就绪"的度量。
- **可选并发派发**：`ConcurrentReputMessageService`（按 topic 分队列并行 dispatch，单线程保序 vs 多线程提吞吐的又一取舍）。
- **生命周期·两处构造**：① 构造器首建（DefaultMessageStore L254-255，按 `enableBuildConsumeQueueConcurrently`（默认 false，Config L464）选单线程/并发版；只造对象不起线程，`start()` L443 才拉起）；② **截断时重建**（`truncateDirtyFiles(long)` L785-817）。装配顺序有讲究：先挂好 dispatcherList、initializeHAService()，再造派发者。
- **截断重建手术**：shutdown 停派发（L794）→ 记 `oldReputFromOffset`（L796）→ 截 CQ 脏尾（L799）+ commitlog 脏尾 → `recoverTopicQueueTable` 复位点表 → **丢旧实例、按配置重新 new**（L805-809）→ 位点回退 `min(oldReputFromOffset, offsetToTruncate)`（L811）→ start（L817）。**why**：commitlog 尾部被截后，旧线程游标与 park 状态全部作废（可能正等一个"永远不会再出现"的 offset）——对带状态的后台线程，`停→截→造新→起` 比原地修状态干净。
- **手术触发源**：`AutoSwitchHAService` L545——controller 模式下从库发现与主 epoch 分叉、截本地尾巴即走此处（即专题篇 §6 Handshake→**Catchup**→Transfer 里 Catchup 阶段的执行落点）。注意区分：`ConsumeQueue`/`TimerLog` 里同名的 `mappedFileQueue.truncateDirtyFiles` 是各文件队列 load 时截自己，**不是**这个方法。
- **优雅退出**：重写的 `shutdown()`（L2674-2687）先自旋等派发追平（50×100ms 轮询 `isCommitLogAvailable()`）再退——停机也不留"CQ 落后 commitlog"的悬案给下次 recover。

> **架构母题收口**：消费者拉消息走的是 ConsumeQueue（逻辑队列），不是 CommitLog（物理）。所以一条消息"落盘成功"后，还要等 ReputMessageService 把它派发到 ConsumeQueue 才可被拉。**写路径不等索引**（否则顺序写被随机写拖垮）——这是 RocketMQ 与 Kafka 共同的"顺序写 + 异步建索引/零拷贝读"路线。

### 4.4 handleDiskFlushAndHA：落盘 + 复制的最后一跳（概要从略）

append 成功只是进了页缓存/池；持久性与多副本承诺在这一步兑现。**两个 CompletableFuture 并行 fork、thenCombine 汇合**（CommitLog L1360-1379）：刷盘座位（SYNC_FLUSH=GroupCommitService 组提交 / ASYNC_FLUSH=500ms 节拍器，默认即此）× 复制座位（needHandleHA 三门 + inSyncReplicas 预检）。要点速记：

- **异步刷盘的 PUT_OK = 进 pageCache**，FLUSH_DISK_TIMEOUT 在该路径不存在；ASYNC_FLUSH+ASYNC_MASTER 是 5.5 发行版默认（源码 + conf 双验）。
- **光配 SYNC_MASTER 不等从库**——还须 inSyncReplicas≥2（默认 1，含 master 自己一票）。
- 非 PUT_OK 状态回传 §1.2 的 retryAnotherBrokerWhenNotStoreOK，全链语义闭环。
- 5.5 常规路径只产出三种落盘态，不产生 SLAVE_NOT_AVAILABLE（映射 case 保留但已成死代码，枚举只剩 timer store 等在用）。

> 完整解剖（三 service 对照 / FlushDiskWatcher 超时受托人 / GroupTransfer 三种 ack 口径 / 数据面往返与角色拓扑 / controller 功能与机制）见姊妹篇 **《rocketmq-刷盘与复制精读.md》**。

### 验收问题（读完 ④ 必须能答）

- [ ] 为什么编码放锁外、append 放锁内？锁内如果做编码会怎样？
- [ ] `END_OF_FILE` 由谁产生、CommitLog 怎么越过这次填充尾？（`DefaultAppendMessageCallback` L2053-2068 写 8 字节 BLANK 并返回 END_OF_FILE；`asyncPutMessage` L1114-1131 换文件重试；读侧 `checkMessageAndReturnSize` 遇 BLANK 返回 size=0 跳文件）
- [ ] 全局一把 `putMessageLock` 是缺点还是设计？它保证了什么？（顺序写的正确性）
- [ ] `asyncPutMessage` 返回 PUT_OK 时，消息一定落盘了吗？一定可被消费了吗？（分别对应 flush 态 / reput 是否跟上——两处都可能"未"）
- [ ] ConsumeQueue 里存的是什么？为什么不直接读 CommitLog？（20 字节索引单元，顺序读 CommitLog + 随机读定位）
- [ ] `FLUSH_DISK_TIMEOUT` 的产生点（handleDiskFlush/FlushManager）和它对客户端重试的影响，把 §1.2、§3.4、§4.1 串成一条线讲清楚。

---

## 全链 recap（一次同步发送）

```
send(L470) ─(autoBatch?)→ sendDefaultImpl(L738) ─选队列/重试/预算→ sendKernelImpl(L911)
   └压缩+组 SendMessageRequestHeader→ MQClientAPIImpl.sendMessage(L534)
      └造 RemotingCommand(SEND_MESSAGE_V2)+setBody→ invokeSync(L583=异步.get)
         └NettyEncoder 写帧[总长|类型:头长|header|body]─TCP→
            broker: NettyDecoder 解帧 → sendMessageExecutor → SendMessageProcessor.processRequest(L89)
               └校验/静态topic重写/Hook/rejectRequest→ asyncPutMessage(L341, 不占线程)
                  └DefaultMessageStore.asyncPutMessage(L645)→ CommitLog.asyncPutMessage(L999)
                     └锁外编码+锁内顺序append→ handleDiskFlushAndHA(L1169, flush+复制future)
                        → 结果 future 回包(putMessageFutureExecutor)
[异步旁路] ReputMessageService.doReput(L2711) → ConsumeQueue + IndexFile（读路径就绪）
```

三层异步呼应：客户端"同步=异步+get"（§2.4）→ broker"asyncPutMessage 释放线程"（§3.4）→ store"flush 走 future + reput 异步建索引"（§4.1/§4.3）。**这条链从头到尾，同步只是异步之上的一个 `.get()`。**

---

## ⑤ 实测验证（本地 5.5.0-bin-release，2026-09-29 实跑）

起了 NameServer + 单主 Broker，用发行版自带 `example/quickstart/Producer` 往 `TopicTest` 发 1000 条（tag `TagA`，body `Hello RocketMQ N`），全 `SEND_OK`。直接对 store 目录二进制逐字节验，坐实 §4 两条核心结论。

**复现配方（JDK 21 有坑，先记）**：发行版 run 脚本硬编 `-Xms8g -Xmx8g` + CMS flag（`UseConcMarkSweepGC` 在 JDK14+ 已删），JDK21 直接跑必挂 → **绕开脚本，手起 java**：

```bash
export ROCKETMQ_HOME=/Users/hongxi/rocketmq-all-5.5.0-bin-release   # 不设会 "Please set ROCKETMQ_HOME" 退出
# NameServer
java -Xms128m -Xmx512m --add-opens java.base/java.nio=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
  -cp "conf:lib/*" org.apache.rocketmq.namesrv.NamesrvStartup
# Broker（自定义 broker-test.conf：小堆 + 独立 storePathRootDir + namesrvAddr=127.0.0.1:9876）
java -Xms256m -Xmx1g -XX:+UseG1GC --add-opens ... -cp "conf:lib/*" \
  org.apache.rocketmq.broker.BrokerStartup -c broker-test.conf
# 发送
export NAMESRV_ADDR=127.0.0.1:9876
java -cp "conf:lib/*" org.apache.rocketmq.example.quickstart.Producer
```

### 5.1 文件层直接印证

- `commitlog/00000000000000000000` 与 `...1073741824` 各 **1,073,741,824 B = 恰好 1 GB**，文件名 = 该文件**起始物理偏移补零到 20 位** → §4.1 "定长 mapped file + 滚动新文件"（且 AllocateMappedFileService 预分配了下一个）。
- `consumequeue/TopicTest/{0,1,2,3}/…` **恰好 4 个队列** = `defaultTopicQueueNums=4`（`autoCreateTopicEnable` 建的），每个 CQ 文件 **6,000,000 B = 300,000 × 20** → §4.3 "每条索引 20 字节、单文件 30 万单元"。
- store 根另有 `abort`（崩溃检测）、`checkpoint`、`index`、`config`、`rocksdbstore`（5.x 可切 RocksDB 版 CQ）、`timerwheel`。

### 5.2 逐条解码 CommitLog（`MessageDecoder` 字段序，大端）

前 5 条：

```
#0 @physic   0 totalSize=240 magicOK  queueId=0 qOff=0 body="Hello RocketMQ 0"
#1 @physic 240            =240         queueId=1 qOff=0 body="Hello RocketMQ 1"
#2 @physic 480            =240         queueId=2 qOff=0
#3 @physic 720            =240         queueId=3 qOff=0
#4 @physic 960            =240         queueId=0 qOff=1   ← 回到队列0，queueOffset 进 1
```

- **物理偏移 `0→240→480→720→960` 连续**，而 `queueId` 轮转 `0,1,2,3,0`、各队列 `queueOffset` 独立递增 → 铁证 §4 "**所有队列混写进一个全局顺序 CommitLog**"：磁盘上按写入物理顺序紧挨着，逻辑队列只是各自的 offset 计数。
- magic 恒 `0xDA77...`（`MESSAGE_MAGIC_CODE=-626843481`）全 `true`；`totalSize` 精确（body "…12/16" 数字多一位 → bodyLen 16→17 → totalSize 240→241）。

### 5.3 ConsumeQueue 单元 ↔ CommitLog 回指（queue 0）

```
CQ#0 (qOff0): phyOff=0    size=240 tagCode=2598919 → CL[queueId=0 qOff=0 body="Hello RocketMQ 0"]
CQ#1 (qOff1): phyOff=960  size=240 tagCode=2598919 → CL[queueId=0 qOff=1 body="Hello RocketMQ 4"]
CQ#2 (qOff2): phyOff=1920 ...                        body="Hello RocketMQ 8"
CQ#3 (qOff3): phyOff=2882 size=241                   body="Hello RocketMQ 12"
```

- 20 字节 = **physicOffset(8) + msgSize(4) + tagsCode(8)**，解出的 physOff 去 CommitLog 定位，queueId 恒 0、body 正是队列 0 收到的 0/4/8/12（跨队列轮转步长 4）→ §4.3 "CQ 存指向 CommitLog 物理偏移的定长索引、消费时再随机读 CommitLog" 完全成立。
- **CQ 内单元下标 == 该队列的 queueOffset**：第 i 个 20B 单元就是队列逻辑第 i 条。这就是消费位点（offset）能直接换算成 CQ 文件字节位置（`offset × 20`）的原因，也是 §4 recap "读路径靠 ConsumeQueue 不靠 CommitLog"的落点。
- `tagCode=2598919 = hashCode("TagA")` 恒定 → broker 侧 tag 过滤预存的就是这个 code。

> **一句话**：书本上"顺序写 + 异步建索引 + CQ 定长回指"这三句，在二进制文件上逐字节对上了。

### 5.4 index 文件哈希索引（按 key 查消息的那条旁路）

**结论先行**：`index/` 下按创建时间命名的单文件，物理 = `40B Header + 5,000,000×4B 槽 + 20,000,000×20B 条目 = 420,000,040 B`（实测文件大小一字不差）。槽数组 + 条目数组两段构成一个"**数组式哈希桶 + 头插法拉链**"。

源码依据（本机 5.5.1，实测跑的是 5.5.0，格式一致）：
- 索引什么 key：`IndexService.buildIndex`（L224）三条——**uniqKey 必建**（L245）、用户 `keys`（L253，quickstart 没设故跳过）、**tag**（L268，tag 走 `buildKey(topic, tags, INDEX_TAG_TYPE)`）。`buildKey(topic,key)=topic+"#"+key`（L217）。
- 落盘：`IndexFile.putKey` → slotPos=`Math.abs(key.hashCode()) % maxHashSlotNum`（L118-120，`maxHashSlotNum=5,000,000`），条目 20B=`keyHash(4)|phyOffset(8)|timeDiff秒(4)|prev(4)`（L145-150），`prev`= 该桶上一个条目位置（链头），槽里改写为"当前条目位置"（头插）。`invalidIndex=0` 即空/链尾哨兵，条目位置从 1 起（0 号位废弃）。
- Header 40B（`IndexHeader`）：beginTs|endTs|beginPhy|endPhy|**slotCount@32**|**indexCount@36**。

**实跑验证**（发 1000 条后）：

```
CommitLog@0 → UNIQ_KEY=C0A80A7874F20C387F44955460620000, body="Hello RocketMQ 0"
buildKey="TopicTest#C0A80A7874F20C387F44955460620000"
javaHash=480860217  → abs 480860217  → slotPos=480860217 % 5,000,000 = 860217
Index header: beginPhy=0  endPhy=241648  slotCount=1001  indexCount=2001
slot[860217] = 1
entry#0 @filePos=20,000,060: keyHash=480860217 (match=true)  phyOffset=0  timeDiff=0s  prev=0
  → CommitLog[0] queueId=0 body="Hello RocketMQ 0"
```

- **哈希链完整闭合**：`buildKey → |javaHash| % 500万 → 槽 → 条目 → phyOffset → 回指 commitlog@0`，body 对得上。slot 地址 `40 + 860217×4 = 3,440,908` ✓，条目地址 `40 + 20,000,000 + 1×20 = 20,000,060` ✓，全部按布局公式命中。
- **Header 计数本身就是拉链的活证据**：发 1000 条 → `indexCount=2001`（1000 条 uniqKey 索引 + 1000 条 tag 索引，indexCount 1-based +1）；`slotCount=1001` = 1000 个各不相同的 uniqKey 槽 **+ 1 个共享槽**——因为 1000 条消息 tag 都是 `TagA`，`buildKey(topic, TAG_TYPE, "TagA")` 恒定 → 同一 keyHash → **同一槽**，1000 条 tag 索引在这一格里用 `prev` 串成一条长链。这正是 §4.3/哈希索引"按 tag 查会退化成长链扫描"的成因。
- `endPhy=241648 = 0x3AFF0` 正是最后一条消息的 commitlog 物理偏移（§5.2 尾部），说明索引覆盖到全部已派发数据。

> **设计要点**：这套"定长槽数组 + 定长条目区 + int 位置当指针做链地址法"——它是**为"按 key/时间查历史消息"设计的磁盘级哈希**，一次寻址进 mmap、无对象分配、无 GC 压力。timeDiff 存"秒级偏移"而非绝对时间戳，是为了 4 字节塞进 20 字节条目——牺牲精度换紧凑，也是取舍。

---

## ⑥ 存储文件布局与一条消息的寻址全链路（发送侧闭环 × 消费侧入口）

> 本节把 §5 实测与消费链路问答收拢成一张"文件地图 + 寻址总账"。所有数字均出自本机 5.5.1 源码或 5.5.0 实跑（含一份 7 月旧 store 里的 `stream-demo-topic` 标本）。

### 6.1 store 根目录文件地图

| 路径 | 尺寸/命名规则 | 角色 |
|---|---|---|
| `commitlog/00000000000000000000` | **1,073,741,824 B = 1G**/文件；文件名 = 起始**物理**偏移（20 位补零） | 消息主体，全局顺序追加；`AllocateMappedFileService` **提前预建**下一个 1G 文件 |
| `consumequeue/<topic>/<qid>/00000000000000000000` | **6,000,000 B = 300,000 × 20**/文件；文件名 = 起始**逻辑字节地址**（=首单元序号×20） | 消费索引；**懒建**（写满才造下一个，对比 commitlog 的预建） |
| `index/20260929235534917` | **420,000,040 B = 40 + 5,000,000×4 + 20,000,000×20**；文件名 = 创建时刻 | key/uniqKey/tag → 物理偏移的磁盘级哈希索引，服务 `mqadmin queryMessage` 类按 key/时间查 |
| `config/consumerOffset.json` | 全量 JSON 重写 | 集群消费位点权威账本（另有 RocksDB/V2 变体） |
| `config/topics.json`、`subscriptionGroup.json` | 同机制周期落盘 | topic/订阅组元数据 |
| `checkpoint` | 三类位点(phy/logic/ckpt) | 崩溃恢复锚 |
| `abort` / `abort.bak` / `lock` | 存在性/文件锁 | 非优雅退出标记；单实例锁 |
| `timerwheel/`、`rocksdbstore/` | — | 定时消息状态机；RocksDB 系存储组件地盆 |

**CQ 文件"6M 是预占位"**：`map(READ_WRITE, 0, fileSize)` 一创建就撑满 6,000,000，未写区全 0——实证的 7 月标本文件 `du` 显示 APFS 真分配了 5.7M（Linux ext4 下通常是稀疏洞），但逻辑上**只有 1 个非零单元**。文件尺寸 ≠ 已写量，300,000 只是滚动天花板。CQ 内**不存 wrotePosition**，重启 load 扫尾部非零单元恢复。

### 6.2 CommitLog 一条记录的完整字节图（标本："Hello RocketMQ 0"，totalSize=240）

```
偏移   字段                  实测值
0      TOTALSIZE(4)          240        ← 与 CQ 单元 size 同值
4      MAGICCODE(4)          -626843481（BLANK 填充记录是 -875286124）
8      BODYCRC(4)
12     QUEUEID(4)            0
16     FLAG(4)
20     QUEUEOFFSET(8)        0          ← 逻辑位点
28     PHYSICALOFFSET(8)     0          ← 自回指物理地址，与 CQ 单元互验
36     SYSFLAG(4)
40     BORN_TIMESTAMP(8)
48     BORN_HOST(8/20)       ← IPv6 时 20B，看 SYSFLAG V6 位（L1414）
56     STORE_TIMESTAMP(8)
64     STORE_HOST(8/20)
72     RECONSUME_TIMES(4)
76     PREPARED_TX_OFFSET(8)
84     BODY_LENGTH(4)        16
88     BODY(16)              "Hello RocketMQ 0"
104    TOPIC_LENGTH(1)+TOPIC  9 "TopicTest"
114    PROPERTIES(126)       "TAGS\u0001TagA\u0002UNIQ_KEY\u0001C0A8…\u0002…"
```

要点：**tag 不是独立段、也不在 CQ 单元里，它在 PROPERTIES 键值串中**；单元里那 8B 是 `"TagA".hashCode()=2598919` 的 tagCode——broker 过滤时的门卫，不参与读。记录 = header + body + topic + properties，**自描述**（body 边界靠 BODY_LENGTH，不靠 CQ 的 size）。

### 6.3 CQ 的 20 字节单元

`CommitLog物理偏移(8) | 记录总长(4) | tagCode(8)`。三条铁律：

1. **单元下标 == 该队列逻辑位点**（跨文件连续编号；空洞由写入侧 `fillPreBlank` 兜底，§4.3）；
2. 定位换算一步：**位点 k ↔ 字节地址 k×20**（`maxOffsetInQueue=(fileFromOffset+wrotePosition)/20`，L268-270）；
3. offset/size 两个字段分别管"在哪"和"多大"，tagCode 只管过滤。

### 6.4 消费位点（consumerOffset.json）

- **账本三套**：集群消费 = broker `config/consumerOffset.json`（V1 JSON；`enableRocksDBStore`→RocksDB；V2→ConfigStorage，BrokerController L380-392 三岔）；广播消费 = 客户端 `~/.rocketmq_offsets/{clientId}/{group}/offsets.json`（LocalFileOffsetStore，位点跟机器走）。
- **语义 = 下一个要消费的位点**：`offset == maxOffset` 合法（等消息）、`> maxOffset` 才钳位（L907-912）——存"上一条"不可能有此表现。
- **内存权威 + 周期落盘**：UPDATE 请求只改内存表（L47-48 `topic@group → queueId → offset`），定时 `persist()` 默认 **5s**（BrokerConfig L90）全量重写 JSON ⇒ **崩溃回退 ≤5s 的推进 = 重复消费在位点维度的根源**；优雅停机补刷一次。
- **全局序号、物理有窗口**：过期删文件后 `offset < minOffset` → 钳到 `minOffset` 照发（L904-906），响应 (min,next,max) 三元组把真实边界带回客户端。**编号系永久、单元窗口有限、账本不知道删数据这回事**。

### 6.5 一条消息的寻址全链路（消费侧视角）

```
consumerOffset.json: k
  → CQ 字节地址 k×20 → 按文件名选 CQ 文件 → 读 20B 单元
      [tagCode 先与订阅表达式 hash 比对，不中则到此为止]
  → 单元给 (phyOffset p, size s)
  → commitlog: 按文件名选文件（起始地址 ≤ p < 起始+1G）、pos = p % 1G → 取 [p, p+s) 完整记录
      （transferMessage 零拷贝引用 mmap buffer，broker 原样进响应）
  → 客户端 MessageDecoder.decodesBatch（PullAPIWrapper L80）解 header+body+properties
      → MessageExt；tag/UNIQ_KEY 从 properties 拆出；批记录由 decodeMessage（L101）拆 N 条
  → 消费成功 → 提交响应里的 nextBeginOffset → 5s 后落入 consumerOffset.json
```

一句话收口三种文件的分工：**commitlog 是正文，consumequeue 是"按位点找正文"的书脊页码，index 是"按关键词找正文"的索引页，consumerOffset.json 是读者夹的书签。**
