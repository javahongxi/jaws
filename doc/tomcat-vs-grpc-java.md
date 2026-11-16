# Tomcat 与 grpc-java 的线程模型对比

本文只谈事实与取舍，不改变 Jaws 的任何代码。写这篇的动机是：Spring Boot Web（spring-boot-starter-web）
默认跑在 Tomcat 上，而 RPC 侧的参照系是 grpc-java——两者都跑在同一个 JVM 上，GC、CPU、内存完全一样，
但**一次请求对应哪个线程、这个线程活多久、socket 由谁读写**这三件事差别很大，直接决定了并发上限、
过载行为、故障半径和泄漏形态。姊妹篇是 Sweet 仓的 `doc/gin-grpc-goroutine-model.md`（gin 与 grpc-go
的 goroutine 模型对比），本文把同一组问题搬到 Java 生态。

结论锚定的版本：Tomcat 10.1.55（Spring Boot 3.5.16 管理的默认版本）、grpc-java 1.84.0
（grpc-netty-shaded，与本仓 `jaws-samples/pom.xml` 的 `grpc.version` 一致）。源码行号分别对应
tomcat 仓 tag `10.1.55` 与 grpc-java 仓 tag `v1.84.0`。

> 性能数字（QPS/延迟）一律以 [`benchmark.md`](benchmark.md) 的实测为准；本文第 6 节是**机制推导**，
> 不给出未经实测的百分比，且 benchmark 覆盖的是 jaws 三种传输，不是 Tomcat vs grpc-java，不能直接引用。

## 一、一句话结论

- **Tomcat（Spring MVC 同步栈）**：Acceptor / Poller 两类固定线程管理连接事件，**每个在途请求独占一个
  worker 线程**，该线程同步读 body、跑 Servlet、写 response——业务线程就是 socket 读写线程；
  但线程占用粒度是**请求**而非连接，空闲 keep-alive 连接不占 worker。
- **grpc-java**：一组**固定数量**的 Netty event loop（默认 = CPU 核数）承担所有连接的读写、解帧、
  流控与 keepalive；服务端**每个 RPC 是共享业务线程池上的一串任务**，由 per-call 的
  `SerializingExecutor` 保证串行；handler 从不直接碰 socket。

与 Go 侧的关键差异先点出来：gin 是"连接即 goroutine"，Tomcat 不是"连接即线程"（NIO connector 把连接
状态放在 Poller 里）；grpc-go 是"每连接 3 个常驻 goroutine + 每 RPC 一个 goroutine"，grpc-java 把连接级
职责全部折叠进共享 event loop，把 RPC 级职责变成池化任务——**Java 两侧都没有"每连接一个线程/每 RPC
一个线程"这回事**。

## 二、对照表

| 维度 | Tomcat（NIO + Spring MVC） | grpc-java（grpc-netty） |
| --- | --- | --- |
| 线程粒度 | 连接事件（Acceptor/Poller，固定）+ 请求（worker，每在途请求一个） | 连接（固定 N 个 event loop 共享）+ RPC（池上的串行任务，非独占线程） |
| 线程生命周期 | worker 处理完一个请求即归还池；下一个请求的数据已到达才在同线程顺序处理，否则连接回 Poller 等待（可能换 worker） | event loop 与进程等长；业务任务与单个回调等长，池线程按需创建/回收 |
| 并发复用 | HTTP/1.1 一条连接同一时刻只有一个在途请求 | HTTP/2 一条连接多路复用 N 个 stream |
| socket 读写归属 | worker 线程自己读写（NIO channel 上的阻塞语义，Poller 负责唤醒） | event loop，handler 只收发已解码消息 |
| 限并发手段 | `maxThreads`（默认 200）+ `maxConnections`（默认 8192）+ `acceptCount`（默认 100） | `maxConcurrentCallsPerConnection`（默认无限）→ HTTP/2 SETTINGS，靠对端遵守 |
| worker pool | 内建 ThreadPoolExecutor（10–200，TaskQueue） | 默认**无界** cached pool，server 与所有 channel 共享同一个 |
| 取消传播 | 客户端断开靠 worker 正在做的读写失败感知 | event loop 观察到 RST_STREAM / deadline 到期 → cancel Context → 回调 onCancel |
| handler 异常半径 | 单个请求 500，worker 线程与连接存活 | 单个 call 以 `Status.fromThrowable` 关闭，连接与其他 stream 不受影响 |
| 异步返回后 | Servlet 3 `AsyncContext` 释放 worker，complete 前请求挂起 | `StreamObserver` 回调可在任意线程发起，写侧回到 event loop |
| 背压体现 | 写 response 阻塞在 worker 线程上（socket 缓冲满） | 流控窗口不足时 `isReady()` 为 false，写积压在 event loop 出站队列 |
| 空闲连接成本 | 1 个 Poller 注册 + socket 缓冲，**不占线程** | 1 份 stream/连接状态 + ByteBuf，**不占线程** |

## 三、Tomcat：Poller 管连接，worker 管请求

### 3.1 三类线程

NIO connector 启动后固定存在的线程只有两个（`NioEndpoint.bind`，NioEndpoint.java:300-304）：

| 线程 | 数量 | 职责 |
| --- | --- | --- |
| Acceptor | 1 | `serverSock.accept()`，受 `maxConnections` 门闩约束，把新连接注册进 Poller |
| Poller | 1（daemon，名字 `<connector>-Poller`） | `selector.select()` 循环，连接可读/可写时派发事件或唤醒阻塞中的 worker |

worker 池在 `AbstractEndpoint.createExecutor`（AbstractEndpoint.java:1215-1226）创建：
`ThreadPoolExecutor(minSpareThreads=10, maxThreads=200, TaskQueue)`，线程名 `http-nio-<port>-exec-N`。
`TaskQueue` 的 offer 语义是"先扩线程到 max，再排队"，与 JDK 默认队列行为相反。

### 3.2 一次请求的线程轨迹

```
Acceptor: accept → 注册进 Poller
Poller:   select 到 OP_READ → processKey → processSocket(OPEN_READ, dispatch=true)
          → executor.execute(SocketProcessor)            ← 唯一一次线程交接
worker:   Http11Processor 解析请求 → 调 Servlet（业务）→ 写 response
          → keep-alive 且下一个请求已到达则继续处理，否则归还线程
```

派发点在 `AbstractEndpoint.processSocket`（AbstractEndpoint.java:1377-1399，`executor.execute(sc)`
在 1393 行）；`SocketProcessor` 定义在 NioEndpoint.java:1741。**从解析请求到写完响应，全部在同一个
worker 线程上顺序发生**——这一点与 gin 的"业务 goroutine 就是 socket goroutine"同构。

### 3.3 阻塞读 body：worker 等，Poller 唤醒

worker 读请求 body 时若内核缓冲无数据，走 `NioSocketWrapper.fillReadBuffer`
（NioEndpoint.java:1286-1338）：置 `readBlocking=true`、注册读兴趣，然后 `readLock.wait(timeout)`；
Poller 在 `processKey` 里看到可读且 `readBlocking` 为真时 `readLock.notify()`（NioEndpoint.java:835-839）。
即：**NIO 的是连接 multiplexing，阻塞的是业务线程**。Tomcat 10.1 已移除独立的 BlockPoller，
阻塞读写统一由主 Poller 唤醒。写侧对称：socket 缓冲满时 worker 阻塞在写锁上等 Poller 的可写通知。

### 3.4 keep-alive：连接状态在 Poller，不在线程

请求结束后连接不关闭，worker 归还池；连接回到 Poller 的 selector 上等待下一个请求。
证据在 `Http11InputBuffer.parseRequestLine`（Http11InputBuffer.java:327-347）：keep-alive 后的
下一轮请求行读取是 `fill(false)` **非阻塞**尝试，javadoc 明说 *"false if no data is available
immediately and thread should be freed"*；无数据即 break 出 service 循环，socket 以 OPEN 状态
交回 Poller。因此 **10k 空闲 keep-alive 连接 ≈ 10k 个 Poller 注册 + socket 缓冲，而不是 10k 个线程**——
这是与 gin（连接即 goroutine，空闲也常驻）的本质区别，也是 NIO connector 相对旧 BIO 的核心收益。
相关默认值：`maxKeepAliveRequests=100`（AbstractEndpoint.java:976，同连接最多服务 100 个请求后关闭）、
`connectionTimeout=60000ms`（http11/Constants.java:26）。

**空闲连接不会一直活着**：`keepAliveTimeout` 未单独设置时回落到 `connectionTimeout`
（AbstractEndpoint.java:787-795），即默认 **60 秒**等不到下一个请求就关。它计的是
"请求结束后等下一个请求"的空闲时间，不是连接总寿命。执行者仍是 Poller：
keep-alive 等待期间 `readTimeout` 被设成 `keepAliveTimeout`
（Http11InputBuffer.parseRequestLine，`wrapper.setReadTimeout(keepAliveTimeout)`），
`Poller.timeout()`（NioEndpoint.java:997，每轮 select 循环调用但受 `timeoutInterval` 节流）
巡检所有注册连接，`now - lastRead > readTimeout` 即置 `SocketTimeoutException` 并派发
`SocketEvent.ERROR` 关连接。所以本节"空闲连接不占线程"的完整图景是：
不占线程，但占 Poller 注册 + 缓冲，且默认最多占 60 秒；计数上限（100 个请求）与
空闲超时是两条独立的回收机制。

### 3.5 默认参数与过载链条

| 参数 | 默认值 | 含义 |
| --- | --- | --- |
| `maxThreads` | 200 | worker 上限 = 同时在途的阻塞请求上限 |
| `minSpareThreads` | 10 | 常驻 worker |
| `maxConnections` | 8192（AbstractEndpoint.java:584） | 连接数门闩，Acceptor 达到上限即停 accept |
| `acceptCount` | 100（AbstractEndpoint.java:751） | OS backlog，门闩满后新连接在此排队，再满则拒绝 |
| `maxQueueSize` | `Integer.MAX_VALUE`（AbstractEndpoint.java:913） | worker 池任务队列容量，默认无界 |

**并发超过 200 时是等待，不是拒绝**。worker 池用的 `TaskQueue` 的 offer 语义与 JDK 默认队列
**相反**（TaskQueue.java:65-84）：线程数未达 max 时 offer 返回 false，逼 `ThreadPoolExecutor`
先创建新线程；达到 200 后才入队——即"先扩线程到 200，再排队"，而非 JDK 默认的
"先排队、队列满才扩线程"。第 201 个请求起全部进 TaskQueue 等待，默认队列无界，
**永远不会触发 `RejectedExecutionException`**。两个细节：

- **排队本身没有超时**：Poller 派发前会 `unreg` 掉该 socket 的读写兴趣
  （NioEndpoint.processKey），而 `Poller.timeout()` 只巡检带 OP_READ/OP_WRITE 兴趣的连接——
  排队中的 socket 不在巡检范围，会一直等到有 worker 空出来，通常是客户端超时先放弃。
- 拒绝只发生在三种情况：显式调小 `maxQueueSize` 且队列满、executor 已 shutdown、
  或连接数限制先顶住（见下）。

过载链条：慢请求占住 worker → 200 个线程耗尽 → 新请求进 TaskQueue **无界排队**（延迟爬升）→
连接数顶到 8192 → Acceptor 停 accept → backlog 100 满 → 连接被拒。
注意**真正的"拒绝"在连接层而非 worker 层**：表现为建不上连接（connection refused/timeout），
而不是"连上了但请求被拒"。
**没有内建的 per-request admission control**，限并发要自己加（信号量 / 网关限流）。
对照 Jaws 业务池的相反取舍（有界队列 + fail-fast 拒绝）见第八节。

### 3.6 虚拟线程：换 executor，不换模型

Spring Boot 3.2+ 设 `spring.threads.virtual.enabled=true` 后，
`TomcatVirtualThreadsWebServerFactoryCustomizer`（spring-boot-autoconfigure 3.5.16，第 39 行）把
protocol handler 的 executor 换成 `VirtualThreadExecutor("tomcat-handler-")`——每请求一个虚拟线程，
"读 body → 业务 → 写 response"的同步代码原样保留，只是阻塞不再 pinned 平台线程。
Tomcat 自身也有 `useVirtualThreads` 开关（AbstractEndpoint.java:1217-1218）。
**线程模型的形状没变，变的是线程的成本**：并发上限从 200 平台线程变成近乎无限的虚拟线程，
过载形态从"排队"转向"下游资源（DB 连接池等）被打穿"，限流责任进一步上移。

## 四、grpc-java：固定 event loop + 共享业务池 + per-call 串行

### 4.1 连接级：没有"每连接三个线程"

grpc-go 每连接常驻 reader / loopy writer / keepalive 三个 goroutine；grpc-java 把这三件事全部放进
**共享的 Netty event loop**：

| 职责 | grpc-java 的承载 |
| --- | --- |
| 读帧、解帧、流控、分发 | worker event loop（`NettyServerHandler` 跑在 channel 绑定的 event loop 上） |
| 写帧、帧聚合 | 同一个 event loop（Netty pipeline 出站） |
| keepalive / 空闲超时 | `KeepAliveManager`，定时任务直接调度在 `ctx.executor()`（event loop）上（NettyServerHandler.java:431），**不是独立线程** |
| deadline 定时 | 全 JVM 共享的单线程 `grpc-timer-%d`（GrpcUtil.java:579-587，daemon） |

event loop 默认数量（netty/Utils.java:124-134、490-507）：boss 1 个，worker **= CPU 核数**——
grpc 特意把 Netty 默认的 2×核数减半（注释写明线程成本含 FD 与 PooledByteBufAllocator chunk）。
线程均为 daemon，名字 `grpc-default-boss-ELG-*` / `grpc-default-worker-ELG-*`，
经 `SharedResourceHolder` 全 JVM 共享。**C 条连接不产生 C 份线程，只产生 C 份协议状态。**

**与 Tomcat 相反，grpc-java 默认不关健康的空闲连接**：`maxConnectionIdle` / `maxConnectionAge`
默认都是禁用（`Long.MAX_VALUE`，NettyServerBuilder.java:77、113-114），没有"空闲 N 秒就关"
的默认行为——这是 gRPC 长连接模型的设计意图：连接昂贵，靠多路复用摊销，能留则留。
默认存在的是**探活而非回收**：

| 机制 | 默认值 | 行为 |
| --- | --- | --- |
| 服务端 keepalive PING | 空闲 **2 小时**后发（GrpcUtil.java:237） | **20 秒**内无 ACK 判死关闭（:242）；对端活着就不动 |
| 客户端 keepalive | 整体禁用（`KEEPALIVE_TIME_NANOS_DISABLED`，GrpcUtil.java:232） | 需显式 `keepAliveTime(...)` 开启 |
| 对端 PING 保护 | `permitKeepAliveTime` 默认 **5 分钟**（GrpcUtil.java:247-248） | 无数据穿插且过频的 PING 累计 strike，达上限发 `GOAWAY ENHANCE_YOUR_CALM (too_many_pings)` 断连 |

想主动回收空闲/老化连接，必须显式配 `maxConnectionIdle(...)` / `maxConnectionAge(...)`。
一句话对比：**Tomcat 默认"空闲 60 秒就关"（HTTP/1.1 连接廉价、随用随建）；
grpc-java 默认"探活通过就永远留着"（HTTP/2 连接昂贵、靠复用摊销）**。

### 4.2 RPC 级：任务，不是线程

服务端默认业务 executor 是 `GrpcUtil.SHARED_CHANNEL_EXECUTOR`（ServerImplBuilder.java:71-72）：
`Executors.newCachedThreadPool`，daemon，名字 `grpc-default-executor-%d`，**无界**，
且 server 与所有客户端 channel 共用这一个池（GrpcUtil.java:557-562）。

每个 RPC 进来时（`ServerImpl.streamCreatedInternal`，ServerImpl.java:475-483、635-636）：

```java
wrappedExecutor = new SerializingExecutor(executor);   // per-call 新建
...
wrappedExecutor.execute(new MethodLookup());           // 方法查找
wrappedExecutor.execute(new HandleServerCall());       // 调 handler
```

后续 `onMessage / onHalfClose / onCancel` 等回调也经同一个 `SerializingExecutor` 排队。
所以准确的说法是：**每个 RPC 一条串行任务队列，而不是每个 RPC 一个线程**。
线程数 ≈ 池的活跃线程数，与在途 RPC 数解耦；同一 call 的回调顺序由队列保证，
不同 call 的任务在池线程上交错执行。这与 grpc-go"每 RPC 新建一个 handler goroutine"形成对照——
grpc-java 省掉了每 RPC 的线程创建，代价是每次回调一次入队 + 唤醒。

两个优化/扩展口：
- `directExecutor()`：改用 `SerializeReentrantCallsDirectExecutor` 并 `stream.optimizeForDirectExecutor()`
  （ServerImpl.java:481-483），回调直接在 event loop 上跑，零交接——**此时 handler 里任何阻塞都会
  卡住该 event loop 上的所有连接**。
- `callExecutor(ServerCallExecutorSupplier)`（1.84.0）：按 call/headers 选择 executor，
  可把不同服务隔离到不同池。

### 4.3 客户端：blocking stub 的 ThreadlessExecutor

客户端默认 executor 与 server 是同一个共享 cached pool（ManagedChannelImplBuilder.java:112），
每个 call 同样包一层 `SerializingExecutor`（ClientCallImpl.java:114）。但 **blocking stub 不走池**：
`ClientCalls.blockingUnaryCall`（ClientCalls.java:157-166）给 call 装一个 `ThreadlessExecutor`，
调用线程在 `waitAndDrain()` 上等待，**等待期间亲自执行排队的回调**：

```java
ThreadlessExecutor executor = new ThreadlessExecutor();
ClientCall<ReqT, RespT> call = channel.newCall(method, callOptions.withExecutor(executor));
ListenableFuture<RespT> responseFuture = futureUnaryCall(call, req);
while (!responseFuture.isDone()) {
    executor.waitAndDrain();    // 调用线程自己 drain 回调队列
}
```

即同步调用的解码后处理发生在**调用方线程**上，不额外占池线程——与 grpc-go 客户端
"发起方 goroutine 直接阻塞等结果"同构，但多了一个"等待者顺手干活"的设计。
async stub 的回调则回到共享池（或用户指定 executor）。

### 4.4 并发上限与异常半径

- `maxConcurrentCallsPerConnection` 默认 `Integer.MAX_VALUE`（NettyServerBuilder.java:105），
  设置后写进 HTTP/2 `SETTINGS_MAX_CONCURRENT_STREAMS` 广告给对端
  （NettyServerHandler.java:255、283）。**约束靠对端遵守 SETTINGS 生效**，
  没有 grpc-go `newHandlerQuota` 那种"配额耗尽就停住本端读循环"的本地闸门。
- handler 抛异常：`HandleServerCall` catch Throwable 后 `stream.close(Status.fromThrowable(ex), ...)`
  （ServerImpl.java:619-623），未知异常映射为 `INTERNAL`。**单个 call 失败，连接与其他 stream 存活**——
  对照 grpc-go 服务端不 recover、panic 打死进程，grpc-java 的故障半径小得多。

## 五、线程数量估算

| 场景 | Tomcat | grpc-java |
| --- | --- | --- |
| 空闲连接 C | 2（Acceptor+Poller），不随 C 增长 | 1 + 核数（event loop）+ 1（timer），不随 C 增长 |
| 在途请求 R | 2 + min(R, 200) | event loop + timer + 池活跃线程 ≈ R（cached pool 无界） |
| 上限 | 200 worker + 8192 连接 + 100 backlog | 无内建上限（线程数随 R 线性膨胀） |

对照 Go 侧：gin ≈ C + 在途数，grpc-go ≈ 3C + R。Java 两侧空闲连接都不贵，
**差异集中在"在途请求用什么承载"**：Tomcat 用有界池（排队），grpc-java 用无界池（膨胀）。

## 六、性能差异表现在哪些方面

先定口径：本节全部是**机制推导**（谁在哪个线程上做什么、每次交接花什么），不是实测结论。
本仓 [`benchmark.md`](benchmark.md) 的数字覆盖 jaws 的 netty / http2 / wire 三种传输，
没有 Tomcat 臂也没有 grpc-java 臂，不能拿来回答"Tomcat 和 grpc-java 谁快"。

### 6.1 连接成本摊销

HTTP/1.1 无多路复用，并发靠多连接，每条新连接付 TCP 握手（TLS 再加 1–2 RTT）与慢启动；
gRPC 走 HTTP/2 一条连接跑满 N 个 stream，握手一次性摊销。但注意 Tomcat NIO 侧空闲连接本身不贵
（3.4 节），**贵的是"每加一点并发就多一套连接状态 + 可能多一个 worker"**，而不是连接常驻线程。
内网东西向高 QPS 小消息，gRPC 占优；低频公网调用，差距被 RTT 淹没。

### 6.2 线程交接次数

- Tomcat：Poller → worker 一次交接（SocketProcessor 入池），之后读、业务、写零交接。
- grpc-java：event loop 解帧 → SerializingExecutor 入队 → 池线程醒来跑 handler →
  响应写回 event loop，至少两次跨线程唤醒；blocking 客户端另有 ThreadlessExecutor 的
  park/unpark。极短 RPC × 极高并发时，这部分占可观测比例的 CPU。
  `directExecutor()` 可以消掉服务端交接，代价见 4.2。

### 6.3 并发上限的形状（比"谁更快"更该关心）

- Tomcat：200 线程 = 200 个并发阻塞请求。**一个慢下游（DB、第三方 HTTP）就能占满全池**，
  快请求跟着排队（排队机制与"为何不拒绝"见 3.5）——thread pool exhaustion 是 Spring MVC
  最经典的过载形态。池是有界的，进程通常活着，表现为延迟雪崩。
- grpc-java：默认 cached pool 无界，慢 handler 不会"排队"而是**持续创建新线程**，
  过载形态是线程数膨胀 → `OutOfMemoryError: unable to create native thread` 或调度恶化。
  生产上应显式 `executor(...)` 换有界池，把过载形态拉回"排队 + 拒绝"。
- 两者都没有内建 per-request 限流；grpc-go 至少有 SETTINGS + handler quota 顶住读循环，
  Java 两侧都把这层留给了应用。

### 6.4 序列化与每请求固定开销

HTTP + JSON：文本头部逐行解析、body 反射序列化、string/byte[] 转换；
gRPC + protobuf：字段号驱动的生成码无反射，HEADERS 走 HPACK 重复头只发索引，
DATA 缓冲走 Netty 池化 ByteBuf。小消息高频调用差异最明显；大 body 时被内存拷贝与带宽主导。

### 6.5 内存画像

| 项 | Tomcat | grpc-java |
| --- | --- | --- |
| 空闲连接 | NioChannel + 读写缓冲 + Poller 注册 | 连接级 HPACK 表 + Http2Connection 状态 + 池化 ByteBuf |
| 在途请求 | processor 有缓存复用（`processorCache`，AbstractEndpoint.processSocket）；body 大小上限靠 Spring 配置 | 每 stream 状态 + 未消费消息积压在入站缓冲；默认接收消息上限 4MB |
| 线程栈 | 至多 200 × 1MB（平台线程） | event loop 固定；cached pool 线程数不设防 |

### 6.6 GC

gRPC 侧对象"多而小、生命周期短"，关键缓冲有池；JSON 侧 header 对象与 body 字节"少而大"。
但真实服务里业务自身分配通常远大于框架分配，**这一项经常被高估**——与姊妹篇同一口径。

## 七、泄漏与故障形态差异

- **Tomcat 的典型泄漏**：慢 Servlet 占住 worker（池耗尽是症状不是泄漏）；`AsyncContext` 忘记
  complete / 超时未处理；**ThreadLocal 未清理**——worker 是复用的，请求 A 留下的 ThreadLocal
  会被同线程的请求 B 读到，这是线程池模型独有的跨请求污染。
- **grpc-java 的典型泄漏**：handler 里做阻塞操作且用了 `directExecutor()` → 卡死 event loop，
  该 loop 上所有连接一起停；stream 不关闭（server streaming 忘了 `onCompleted`）→ per-call
  状态与 SerializingExecutor 队列常驻；无界池下慢回调堆积 → 线程膨胀。
- **异常半径**：Tomcat 一个未捕获异常 → 该请求 500，worker 与连接存活；
  grpc-java 一个 handler 异常 → 该 call INTERNAL，连接存活；
  （参照：grpc-go 一个 panic → 进程退出。）Java 两侧都不需要"recovery interceptor 保进程"，
  但都需要防止**单点慢/单点漏**扩散成池级/loop 级故障。

## 八、与 Jaws 的关系

Jaws 的服务端线程模型与 grpc-java 同构，而不是 Tomcat 式 thread-per-request：

- **三种传输统一在 `serverExecutor.execute()` 处卸载 event loop**：
  `NettyChannelHandler.channelRead`（NettyChannelHandler.java:99）、
  `Http2StreamServerHandler.dispatch`（Http2StreamServerHandler.java:317）、
  `WireStreamServerHandler`（WireStreamServerHandler.java:713-717）。
  event loop 只做解帧与派发，业务全部跑在业务池上——与 4.2 节 grpc-java 的
  JumpToApplicationThread 是同一个形状。
- **同形之下，派发粒度不同**：grpc-java 一个 call 有多个异步事件
  （MethodLookup / HandleServerCall / onMessage×N / onHalfClose / onCancel），
  每个事件是独立任务，全部排进该 call 专属的 SerializingExecutor 队列——
  串行是显式机制，哪怕 handler 还在跑，event loop 投递的 onCancel 也得入队等。
  Jaws 裸 TCP 是单帧 unary：**一个请求帧 = 一个 Runnable**
  （NettyChannelHandler.java:99-105），"业务 → 编码 → 写回"的顺序由
  CompletableFuture 链（handle/thenAccept，NettyChannelHandler.java:159-188）保证，
  没有 per-call 队列包装。等于 grpc 用"每 call 一条队列"做的事，Jaws 用
  "每请求一条 future 链"做了——单帧模型下更轻，省掉每 call 一个队列对象与入队同步；
  若裸 TCP 将来支持多帧流式，就会遇到 grpc 同款问题，届时才需要 per-call 串行层
  （wire/HTTP2 的流式派发已在各自 handler 里处理帧序）。过载闸门位置也随之不同：
  Jaws 在池入口接住 `RejectedExecutionException` 当场回 `SERVICE_REJECT`
  （NettyChannelHandler.java:106-116），grpc-java 的背压则积压在 per-call 队列里。
- **wire 流式不需要另加 SerializingExecutor**：它解决的问题已被两个现有机制覆盖——
  入站消息的串行投递由 `StreamSubject` 的 drain 循环承担（单 owner drain、terminal
  严格排在所有 item 之后、锁外投递，StreamSubject.java:121-182），出站帧序由
  `commitFrame` 把所有写弹回 stream 的 event loop 保证（WireStreamServerHandler.java:670-676，
  避免业务线程 DATA 排到 in-loop END_STREAM 之后）；unary 则由 `dispatched` 标志
  保证每 call 只派发一个任务（:506-533）。即 wire 的串行层是"drain 循环 + event loop"，
  而非"每 call 一个 executor 队列"，形状不同、职责等价。一处真实的边界差异：
  grpc 的串行层把回调**入队到业务池**，event loop 永不执行应用代码；StreamSubject
  的 drain 是"谁抢到 drain 权谁投递"，event loop 解码后调 onNext 时若业务 subscriber
  已挂上，observer.onNext 就直接跑在 event loop 上——隐含契约是"observer 只做轻量
  桥接，重活留给派发出去的业务线程"，契约被打破时才需要考虑给入站投递加 executor
  卸载。另注意：grpc 的 SerializingExecutor 并不解决出站并发（应用多线程并发调
  onNext 在 grpc-java 里同样不安全），wire 的 commitFrame 解决的恰是出站顺序——
  两者是互补关系，不是同一个东西的两种实现。
- **两处刻意的取舍不同**：Jaws 用**有界** `EagerThreadPoolExecutor` +
  `AbortPolicyWithStats` fail-fast（AbstractNettyServer.java:95-103，min/max/queue 全部配置驱动），
  拒绝时由 pipeline 回错误响应，而不是 grpc-java 默认的无界 cached pool；
  Jaws 的 boss/worker event loop 是**非 daemon**（AbstractNettyServer.java:106-110，main 退出后靠它保活 JVM），
  grpc-java 默认全 daemon。
- **消费端同步调用**：`DefaultResponseFuture.getValue()` 委托 `CompletableFuture.get()`
  阻塞调用线程（DefaultResponseFuture.java:48-51），响应在 event loop 上解码后 complete future。
  对照 grpc-java 的 ThreadlessExecutor（调用线程等待期间亲自 drain 回调）：Jaws 的 complete 路径
  只有轻量字段赋值，没有重回调需要调用线程代跑，wait/notify 语义已足够。
- **共存形态**：`jaws-sample-boot-consumer` 这类模块同时引入 spring-boot-starter-web 与 jaws 引用，
  一次"HTTP 入口 → RPC 调用"的请求里，Tomcat worker 阻塞在 future 上等 jaws 的响应——
  排查"慢在哪"时要先分清是 Tomcat 池排队、RPC 在途、还是 provider 业务池排队，三段各有自己的线程归属。

## 九、自查命令

```bash
# Tomcat 10.1.55 关键行（本机源码仓）
cd ~/github/tomcat
git grep -n "maxThreads = 200\|acceptCount = 100\|maxConnections = 8 \* 1024" 10.1.55 -- java/org/apache/tomcat/util/net/AbstractEndpoint.java
git grep -n "new Poller()\|readLock.wait" 10.1.55 -- java/org/apache/tomcat/util/net/NioEndpoint.java

# grpc-java v1.84.0 关键行
cd ~/github/grpc-java
git grep -n "newCachedThreadPool" v1.84.0 -- core/src/main/java/io/grpc/internal/GrpcUtil.java
git grep -n "new SerializingExecutor" v1.84.0 -- core/src/main/java/io/grpc/internal/ServerImpl.java
git grep -n "waitAndDrain" v1.84.0 -- stub/src/main/java/io/grpc/stub/ClientCalls.java
git grep -n "numEventLoops = NettyRuntime.availableProcessors()" v1.84.0 -- netty/src/main/java/io/grpc/netty/Utils.java

# 运行时观察：两类线程的名字与数量
jcmd <pid> Thread.print | grep -c "http-nio-.*-exec-"        # Tomcat worker
jcmd <pid> Thread.print | grep -c "grpc-default-executor-"   # gRPC 业务池
jcmd <pid> Thread.print | grep -c "grpc-default-worker-ELG"  # gRPC event loop

# Spring Boot actuator（需暴露 metrics）
curl -s localhost:8080/actuator/metrics/tomcat.threads.busy
curl -s localhost:8080/actuator/metrics/tomcat.threads.config.max
```

## 十、关联文档

- 性能实测（jaws 三种传输）：[`benchmark.md`](benchmark.md)
- HTTP/2 与裸 TCP 的帧层差异：[`http2-vs-tcp.md`](http2-vs-tcp.md)
- Netty 传输性能分析：[`netty-performance.md`](netty-performance.md)
- 全链路异步设计：[`async-full-chain.md`](async-full-chain.md)
- 整体架构：[`architecture.md`](architecture.md)
- 姊妹篇（Go 侧，外部仓）：Sweet `doc/gin-grpc-goroutine-model.md`