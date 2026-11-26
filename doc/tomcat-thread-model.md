# tomcat 线程模型精读

本文只谈事实与取舍，不改变 Jaws 的任何代码。写这篇的动机是：Spring Boot Web（spring-boot-starter-web）
默认跑在 tomcat 上，而 `jaws-sample-boot-consumer` 这类模块同时引入 web 与 jaws 引用——排查
"HTTP 入口 → RPC 调用"慢在哪，先要弄清 tomcat 侧**一次请求对应哪个线程、这个线程活多久、
socket 由谁读写**这三件事，它们直接决定了并发上限、过载行为与泄漏形态。
grpc-java 侧的线程模型（与本仓三种传输同构的参照系）单独成篇：[`grpc-java-thread-model.md`](grpc-java-thread-model.md)。

结论锚定的版本：tomcat 10.1.55（Spring Boot 3.5.16 管理的默认版本）。源码行号对应
tomcat 仓 tag `10.1.55`。

> 性能数字（QPS/延迟）一律以 [`benchmark.md`](benchmark.md) 的实测为准；本文的过载与开销分析是
> **机制推导**，不给出未经实测的百分比，且 benchmark 覆盖的是 jaws 三种传输，不是 tomcat，不能直接引用。

## 一、一句话结论

**tomcat（Spring MVC 同步栈）**：Acceptor / Poller 两类固定线程管理连接事件，**每个在途请求独占一个
worker 线程**，该线程同步读 body、跑 Servlet、写 response——业务线程就是 socket 读写线程；
但线程占用粒度是**请求**而非连接，空闲 keep-alive 连接不占 worker。

与 Go 侧的关键差异先点出来：gin 是"连接即 goroutine"，tomcat 不是"连接即线程"（NIO connector 把连接
状态放在 Poller 里）。姊妹篇是 Sweet 仓的 `doc/gin-grpc-goroutine-model.md`（gin 与 grpc-go
的 goroutine 模型对比），本文把 tomcat 这一侧的问题在 Java 生态里讲透。

## 二、Poller 管连接，worker 管请求

### 2.1 三类线程

NIO connector 启动后固定存在的线程只有两个（`NioEndpoint.bind`，NioEndpoint.java:300-304）：

| 线程 | 数量 | 职责 |
| --- | --- | --- |
| Acceptor | 1 | `serverSock.accept()`，受 `maxConnections` 门闩约束，把新连接注册进 Poller |
| Poller | 1（daemon，名字 `<connector>-Poller`） | `selector.select()` 循环，连接可读/可写时派发事件或唤醒阻塞中的 worker |

worker 池在 `AbstractEndpoint.createExecutor`（AbstractEndpoint.java:1215-1226）创建：
`ThreadPoolExecutor(minSpareThreads=10, maxThreads=200, TaskQueue)`，线程名 `http-nio-<port>-exec-N`。
`TaskQueue` 的 offer 语义是"先扩线程到 max，再排队"，与 JDK 默认队列行为相反。

### 2.2 一次请求的线程轨迹

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

### 2.3 阻塞读 body：worker 等，Poller 唤醒

worker 读请求 body 时若内核缓冲无数据，走 `NioSocketWrapper.fillReadBuffer`
（NioEndpoint.java:1286-1338）：置 `readBlocking=true`、注册读兴趣，然后 `readLock.wait(timeout)`；
Poller 在 `processKey` 里看到可读且 `readBlocking` 为真时 `readLock.notify()`（NioEndpoint.java:835-839）。
即：**NIO 的是连接 multiplexing，阻塞的是业务线程**。tomcat 10.1 已移除独立的 BlockPoller，
阻塞读写统一由主 Poller 唤醒。写侧对称：socket 缓冲满时 worker 阻塞在写锁上等 Poller 的可写通知。

### 2.4 keep-alive：连接状态在 Poller，不在线程

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

### 2.5 默认参数与过载链条

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
200 线程 = 200 个并发阻塞请求，**一个慢下游（DB、第三方 HTTP）就能占满全池**，
快请求跟着排队——thread pool exhaustion 是 Spring MVC 最经典的过载形态。
池是有界的，进程通常活着，表现为延迟雪崩。对照 Jaws 业务池的相反取舍见第六节。

### 2.6 虚拟线程：换 executor，不换模型

Spring Boot 3.2+ 设 `spring.threads.virtual.enabled=true` 后，
`TomcatVirtualThreadsWebServerFactoryCustomizer`（spring-boot-autoconfigure 3.5.16，第 39 行）把
protocol handler 的 executor 换成 `VirtualThreadExecutor("tomcat-handler-")`——每请求一个虚拟线程，
"读 body → 业务 → 写 response"的同步代码原样保留，只是阻塞不再 pinned 平台线程。
tomcat 自身也有 `useVirtualThreads` 开关（AbstractEndpoint.java:1217-1218）。
**线程模型的形状没变，变的是线程的成本**：并发上限从 200 平台线程变成近乎无限的虚拟线程，
过载形态从"排队"转向"下游资源（DB 连接池等）被打穿"，限流责任进一步上移。

## 三、线程数量与内存画像

| 场景 | 线程数 |
| --- | --- |
| 空闲连接 C | 2（Acceptor+Poller），不随 C 增长 |
| 在途请求 R | 2 + min(R, 200) |
| 上限 | 200 worker + 8192 连接 + 100 backlog |

| 项 | 成本 |
| --- | --- |
| 空闲连接 | NioChannel + 读写缓冲 + Poller 注册，**不占线程** |
| 在途请求 | processor 有缓存复用（`processorCache`，AbstractEndpoint.processSocket）；body 大小上限靠 Spring 配置 |
| 线程栈 | 至多 200 × 1MB（平台线程）；虚拟线程模式下栈在堆上，成本画像另算 |

对照 Go 侧：gin ≈ C + 在途数（连接即 goroutine，空闲也常驻）。tomcat 空闲连接不贵，
**贵的是"每加一点并发就多一个 worker"**——并发承载是有界池，过载形态是排队而非膨胀。

## 四、泄漏与故障形态

- **典型泄漏**：慢 Servlet 占住 worker（池耗尽是症状不是泄漏）；`AsyncContext` 忘记
  complete / 超时未处理；**ThreadLocal 未清理**——worker 是复用的，请求 A 留下的 ThreadLocal
  会被同线程的请求 B 读到，这是线程池模型独有的跨请求污染。
- **异常半径**：一个未捕获异常 → 该请求 500，worker 与连接存活；不需要"保进程"的兜底，
  但要防止**单点慢/单点漏**扩散成池级故障。
- **异步返回后**：Servlet 3 `AsyncContext` 释放 worker，complete 前请求挂起——
  这是同步栈里唯一"请求不独占线程"的口子，代价是生命周期管理责任移交应用。

## 五、自查命令

```bash
# tomcat 10.1.55 关键行（本机源码仓）
cd ~/github/tomcat
git grep -n "maxThreads = 200\|acceptCount = 100\|maxConnections = 8 \* 1024" 10.1.55 -- java/org/apache/tomcat/util/net/AbstractEndpoint.java
git grep -n "new Poller()\|readLock.wait" 10.1.55 -- java/org/apache/tomcat/util/net/NioEndpoint.java

# 运行时观察：worker 线程的名字与数量
jcmd <pid> Thread.print | grep -c "http-nio-.*-exec-"

# Spring Boot actuator（需暴露 metrics）
curl -s localhost:8080/actuator/metrics/tomcat.threads.busy
curl -s localhost:8080/actuator/metrics/tomcat.threads.config.max
```

## 六、与 Jaws 的关系

- **模型不同构**：Jaws 服务端是 event loop + 业务池（三种传输统一在 `serverExecutor.execute()`
  处卸载 event loop），不是 tomcat 式 thread-per-request，详见
  [`grpc-java-thread-model.md`](grpc-java-thread-model.md) 第四节。
- **池的语义同源、取舍相反**：Jaws 的 `EagerThreadPoolExecutor` 明言灵感来自 tomcat
  `StandardThreadExecutor`（EagerThreadPoolExecutor.java:14-29），继承了"先扩线程到 max，
  再排队"的 offer 语义；但 tomcat 默认队列**无界**（永不拒绝、延迟雪崩），Jaws 用**有界**队列 +
  `AbortPolicyWithStats` fail-fast（AbstractNettyServer.java:95-103，min/max/queue 全部配置驱动），
  拒绝时由 pipeline 当场回 `SERVICE_REJECT`（NettyChannelHandler.java:106-116），
  让客户端立刻 failover 而不是排队等到超时。
- **共存形态**：`jaws-sample-boot-consumer` 这类模块同时引入 spring-boot-starter-web 与 jaws 引用，
  一次"HTTP 入口 → RPC 调用"的请求里，tomcat worker 阻塞在 future 上等 jaws 的响应——
  排查"慢在哪"时要先分清是 tomcat 池排队、RPC 在途、还是 provider 业务池排队，三段各有自己的线程归属。

## 七、关联文档

- grpc-java 线程模型（与 Jaws 对照）：[`grpc-java-thread-model.md`](grpc-java-thread-model.md)
- Netty 传输性能分析：[`netty-performance.md`](netty-performance.md)
- HTTP/2 与裸 TCP 的帧层差异：[`http2-vs-tcp.md`](http2-vs-tcp.md)
- 全链路异步设计：[`async-full-chain.md`](async-full-chain.md)
- 整体架构：[`architecture.md`](architecture.md)
- 性能实测（jaws 三种传输）：[`benchmark.md`](benchmark.md)
- 姊妹篇（Go 侧，外部仓）：Sweet `doc/gin-grpc-goroutine-model.md`