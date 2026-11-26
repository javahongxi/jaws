# grpc-java 线程模型精读：与 Jaws 对照

本文只谈事实与取舍，不改变 Jaws 的任何代码。grpc-java 是 Jaws 传输层的参照实现——
两者都跑在同一个 JVM 上，GC、CPU、内存完全一样，但**一次请求对应哪个线程、这个线程活多久、
socket 由谁读写**这三件事的取舍，直接决定了并发上限、过载行为、故障半径和泄漏形态。
本文以 grpc-java 的机制为主线，逐节对照 Jaws 三种传输（裸 TCP / HTTP/2 / wire）的同与不同。
tomcat（thread-per-request 一极）单独成篇：[`tomcat-thread-model.md`](tomcat-thread-model.md)。

结论锚定的版本：grpc-java 1.84.0（grpc-netty-shaded，与本仓 `jaws-samples/pom.xml` 的
`grpc.version` 一致）。源码行号对应 grpc-java 仓 tag `v1.84.0`；Jaws 侧行号对应当前仓库。

> 性能数字（QPS/延迟）一律以 [`benchmark.md`](benchmark.md) 的实测为准；本文的开销分析是**机制推导**，
> 不给出未经实测的百分比，且 benchmark 覆盖的是 jaws 三种传输，不是 grpc-java，不能直接引用。

## 一、一句话结论

**grpc-java**：一组**固定数量**的 Netty event loop（默认 = CPU 核数）承担所有连接的读写、解帧、
流控与 keepalive；服务端**每个 RPC 是共享业务线程池上的一串任务**，由 per-call 的
`SerializingExecutor` 保证串行；handler 从不直接碰 socket。

**Jaws 与它同构**：三种传输统一在 `serverExecutor.execute()` 处卸载 event loop，
event loop 只做解帧与派发，业务全部跑在业务池上。同形之下有三处关键取舍不同：
派发粒度（每请求一个任务 vs 每 call 一串任务）、过载闸门（池入口 fail-fast vs 无界池膨胀）、
event loop 线程属性（非 daemon 保活 vs 全 daemon）——详见第四节。

与 Go 侧的关键差异先点出来：grpc-go 是"每连接 3 个常驻 goroutine + 每 RPC 一个 goroutine"，
grpc-java 把连接级职责全部折叠进共享 event loop，把 RPC 级职责变成池化任务——
**Java 侧没有"每连接一个线程/每 RPC 一个线程"这回事**。姊妹篇是 Sweet 仓的
`doc/gin-grpc-goroutine-model.md`（gin 与 grpc-go 的 goroutine 模型对比）。

## 二、连接级：固定 event loop，没有"每连接三个线程"

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

**grpc-java 默认不关健康的空闲连接**：`maxConnectionIdle` / `maxConnectionAge`
默认都是禁用（`Long.MAX_VALUE`，NettyServerBuilder.java:77、113-114），没有"空闲 N 秒就关"
的默认行为——这是 gRPC 长连接模型的设计意图：连接昂贵，靠多路复用摊销，能留则留。
默认存在的是**探活而非回收**：

| 机制 | 默认值 | 行为 |
| --- | --- | --- |
| 服务端 keepalive PING | 空闲 **2 小时**后发（GrpcUtil.java:237） | **20 秒**内无 ACK 判死关闭（:242）；对端活着就不动 |
| 客户端 keepalive | 整体禁用（`KEEPALIVE_TIME_NANOS_DISABLED`，GrpcUtil.java:232） | 需显式 `keepAliveTime(...)` 开启 |
| 对端 PING 保护 | `permitKeepAliveTime` 默认 **5 分钟**（GrpcUtil.java:247-248） | 无数据穿插且过频的 PING 累计 strike，达上限发 `GOAWAY ENHANCE_YOUR_CALM (too_many_pings)` 断连 |

表中第一行与第三行是**同一 PING 机制的出/入两个方向**：前者是 KeepAliveManager
"我探你活没活"（本端发出），后者是 KeepAliveEnforcer"我管你怎么探我"（本端收到，
`MAX_PING_STRIKES=2`，KeepAliveEnforcer.java:27）。入站管制是"能留则留"政策的必要配套：
没有它，对端可用高频 PING 让连接永远"健康"地活着、白占资源。数值上三处咬合：
客户端 `keepAliveTime` 必须 ≥ 对端 `permitKeepAliveTime`，否则自己的探活会被对端踢掉；
无在途 call 时收 PING 的隐式许可间隔恰好也是 2 小时（`IMPLICIT_PERMIT_TIME_NANOS`，
KeepAliveEnforcer.java:29），与发送间隔对齐，默认行为自洽；有数据收发时 enforcer
重置计数，活跃流量中的 PING 永远合法——忙时靠数据保活，闲时才轮到 PING。

想主动回收空闲/老化连接，必须显式配 `maxConnectionIdle(...)` / `maxConnectionAge(...)`。
一句话对照：tomcat 默认"空闲 60 秒就关"（HTTP/1.1 连接廉价、随用随建，见
[`tomcat-thread-model.md`](tomcat-thread-model.md) 第 2.4 节）；
grpc-java 默认"探活通过就永远留着"（HTTP/2 连接昂贵、靠复用摊销）。

## 三、RPC 级：任务，不是线程

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

## 四、与 Jaws 对照：同形之下的三处取舍

Jaws 的服务端线程模型与 grpc-java 同构，而不是 tomcat 式 thread-per-request：

- **三种传输统一在 `serverExecutor.execute()` 处卸载 event loop**：
  `NettyChannelHandler.channelRead`（NettyChannelHandler.java:99）、
  `Http2StreamServerHandler.dispatch`（Http2StreamServerHandler.java:317）、
  `WireStreamServerHandler`（WireStreamServerHandler.java:725）。
  event loop 只做解帧与派发，业务全部跑在业务池上——与第三节 grpc-java 的
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
  `commitFrame` 把所有写弹回 stream 的 event loop 保证（WireStreamServerHandler.java:678-684，
  避免业务线程 DATA 排到 in-loop END_STREAM 之后）；unary 则由 `dispatched` 标志
  保证每 call 只派发一个任务（:506-510）。即 wire 的串行层是"drain 循环 + event loop"，
  而非"每 call 一个 executor 队列"，形状不同、职责等价。一处真实的边界差异：
  grpc 的串行层把回调**入队到业务池**，event loop 永不执行应用代码；StreamSubject
  的 drain 是"谁抢到 drain 权谁投递"，event loop 解码后调 onNext 时若业务 subscriber
  已挂上，observer.onNext 就直接跑在 event loop 上——契约是"observer 只做轻量
  桥接，重活自行交接到自己的 executor"（已明文写入 `StreamObserver` 的 javadoc
  threading contract，含零交接与隐式背压的取舍说明），契约被打破时才需要考虑给入站投递加 executor
  卸载。另注意：grpc 的 SerializingExecutor 并不解决出站并发（应用多线程并发调
  onNext 在 grpc-java 里同样不安全），wire 的 commitFrame 解决的恰是出站顺序——
  两者是互补关系，不是同一个东西的两种实现。
- **两处刻意的取舍不同**：Jaws 用**有界** `EagerThreadPoolExecutor` +
  `AbortPolicyWithStats` fail-fast（AbstractNettyServer.java:95-103，min/max/queue 全部配置驱动），
  拒绝时由 pipeline 回错误响应，而不是 grpc-java 默认的无界 cached pool；
  Jaws 的 boss/worker event loop 是**非 daemon**（AbstractNettyServer.java:106-110，main 退出后靠它保活 JVM），
  grpc-java 默认全 daemon。

## 五、客户端：blocking stub 的 ThreadlessExecutor

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

**对照 Jaws 消费端同步调用**：`DefaultResponseFuture.getValue()` 委托 `CompletableFuture.get()`
阻塞调用线程（DefaultResponseFuture.java:48-51），响应在 event loop 上解码后 complete future。
对照 ThreadlessExecutor（调用线程等待期间亲自 drain 回调）：Jaws 的 complete 路径
只有轻量字段赋值，没有重回调需要调用线程代跑，wait/notify 语义已足够。

## 六、并发上限、交接成本与异常半径

### 6.1 并发上限与异常半径

- `maxConcurrentCallsPerConnection` 默认 `Integer.MAX_VALUE`（NettyServerBuilder.java:105），
  设置后写进 HTTP/2 `SETTINGS_MAX_CONCURRENT_STREAMS` 广告给对端
  （NettyServerHandler.java:255、283）。**约束靠对端遵守 SETTINGS 生效**，
  没有 grpc-go `newHandlerQuota` 那种"配额耗尽就停住本端读循环"的本地闸门。
- handler 抛异常：`HandleServerCall` catch Throwable 后 `stream.close(Status.fromThrowable(ex), ...)`
  （ServerImpl.java:619-623），未知异常映射为 `INTERNAL`。**单个 call 失败，连接与其他 stream 存活**——
  对照 grpc-go 服务端不 recover、panic 打死进程，grpc-java 的故障半径小得多。
- 两者都没有内建 per-request 限流；grpc-go 至少有 SETTINGS + handler quota 顶住读循环，
  Java 侧把这层留给了应用。Jaws 的答案是把闸门放在业务池入口：有界队列 + fail-fast 拒绝，
  当场回错误响应让客户端 failover（第四节）。

### 6.2 线程交接次数（机制推导，非实测）

event loop 解帧 → SerializingExecutor 入队 → 池线程醒来跑 handler → 响应写回 event loop，
至少两次跨线程唤醒；blocking 客户端另有 ThreadlessExecutor 的 park/unpark。
极短 RPC × 极高并发时，这部分占可观测比例的 CPU。`directExecutor()` 可以消掉服务端交接，
代价见第三节。Jaws 裸 TCP 的 unary 路径交接次数相同（event loop → 业务池 → 写回 event loop），
但省掉了 per-call 队列的入队同步；全链路交接的逐层拆解见 [`async-full-chain.md`](async-full-chain.md)。

### 6.3 过载形态与线程数量估算

| 场景 | grpc-java | Jaws |
| --- | --- | --- |
| 空闲连接 C | 1 + 核数（event loop）+ 1（timer），不随 C 增长 | boss 1 + worker 默认 2×核数，不随 C 增长 |
| 在途请求 R | event loop + timer + 池活跃线程 ≈ R（cached pool 无界） | event loop + 池活跃线程 ≤ maxWorkerThreads（有界） |
| 过载形态 | 慢 handler 不"排队"而是**持续创建新线程** → 线程膨胀 → `OutOfMemoryError: unable to create native thread` 或调度恶化 | 池满即拒（`SERVICE_REJECT` / 503 / UNAVAILABLE），延迟不雪崩，客户端立刻 failover |

grpc-java 默认 cached pool 无界，生产上应显式 `executor(...)` 换有界池，把过载形态拉回
"排队 + 拒绝"——Jaws 默认就是这个形状（`EagerThreadPoolExecutor` 的"先扩线程再排队"语义
源自 tomcat `StandardThreadExecutor`，见 [`tomcat-thread-model.md`](tomcat-thread-model.md) 第六节）。

### 6.4 内存画像

| 项 | grpc-java |
| --- | --- |
| 空闲连接 | 连接级 HPACK 表 + Http2Connection 状态 + 池化 ByteBuf，**不占线程** |
| 在途请求 | 每 stream 状态 + 未消费消息积压在入站缓冲；默认接收消息上限 4MB |
| 线程栈 | event loop 固定；cached pool 线程数不设防 |

## 七、泄漏与故障形态

- **典型泄漏**：handler 里做阻塞操作且用了 `directExecutor()` → 卡死 event loop，
  该 loop 上所有连接一起停；stream 不关闭（server streaming 忘了 `onCompleted`）→ per-call
  状态与 SerializingExecutor 队列常驻；无界池下慢回调堆积 → 线程膨胀。
- **异常半径**：一个 handler 异常 → 该 call INTERNAL，连接存活；
  （参照：grpc-go 一个 panic → 进程退出。）不需要"recovery interceptor 保进程"，
  但需要防止**单点慢/单点漏**扩散成池级/loop 级故障。
- Jaws 侧对应的防线：event loop 上不跑业务（第四节）、出站帧统一经 `commitFrame`
  弹回 event loop（避免 DATA/END_STREAM 乱序）、`StreamObserver` javadoc 的 threading contract
  约束 observer 只做轻量桥接——契约被打破时的症状与 grpc-java `directExecutor()` 卡 loop 同形。

## 八、共存形态：与 tomcat 同 JVM

`jaws-sample-boot-consumer` 这类模块同时引入 spring-boot-starter-web 与 jaws 引用，
一次"HTTP 入口 → RPC 调用"的请求里，tomcat worker 阻塞在 future 上等 jaws 的响应——
排查"慢在哪"时要先分清是 tomcat 池排队、RPC 在途、还是 provider 业务池排队，三段各有自己的线程归属。
tomcat 侧的机制详见 [`tomcat-thread-model.md`](tomcat-thread-model.md)。

## 九、自查命令

```bash
# grpc-java v1.84.0 关键行（本机源码仓）
cd ~/github/grpc-java
git grep -n "newCachedThreadPool" v1.84.0 -- core/src/main/java/io/grpc/internal/GrpcUtil.java
git grep -n "new SerializingExecutor" v1.84.0 -- core/src/main/java/io/grpc/internal/ServerImpl.java
git grep -n "waitAndDrain" v1.84.0 -- stub/src/main/java/io/grpc/stub/ClientCalls.java
git grep -n "numEventLoops = NettyRuntime.availableProcessors()" v1.84.0 -- netty/src/main/java/io/grpc/netty/Utils.java

# 运行时观察：两类线程的名字与数量
jcmd <pid> Thread.print | grep -c "grpc-default-executor-"   # gRPC 业务池
jcmd <pid> Thread.print | grep -c "grpc-default-worker-ELG"  # gRPC event loop
```

## 十、关联文档

- tomcat 线程模型（thread-per-request 一极）：[`tomcat-thread-model.md`](tomcat-thread-model.md)
- 全链路异步设计：[`async-full-chain.md`](async-full-chain.md)
- HTTP/2 与裸 TCP 的帧层差异：[`http2-vs-tcp.md`](http2-vs-tcp.md)
- Netty 传输性能分析：[`netty-performance.md`](netty-performance.md)
- 整体架构：[`architecture.md`](architecture.md)
- 性能实测（jaws 三种传输）：[`benchmark.md`](benchmark.md)
- 姊妹篇（Go 侧，外部仓）：Sweet `doc/gin-grpc-goroutine-model.md`