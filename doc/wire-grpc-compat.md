# jaws-wire：零 grpc-java 依赖，却与 gRPC 双向互通

> 本文讲 `jaws-wire` 模块——它把 gRPC 的**线格式**（wire format）从零实现了一遍，不依赖 grpc-java 一行代码，却能跟原生 gRPC、跟 `grpcurl` 双向调通。它是 Jaws "对外咬合"最纯粹的一处：**自己单方面长成 gRPC 的齿形**，而不是要求 gRPC 来接它。读完这篇，你手里就有一份"gRPC 在 HTTP/2 上到底规定了什么"的活文档。

相关：模块地图见 [architecture.md](architecture.md)，编解码通用面见 [codec-comparison.md](codec-comparison.md)，与 Dubbo 的能力对比见 [dubbo-comparison.md](dubbo-comparison.md)。

## 1. 为什么要自己实现 gRPC 线格式

Jaws 有两种协议：以 Java 接口即契约的 **jaws 协议**（自定义二进制头），和以 IDL 为契约、对等 gRPC 的 **wire 协议**。wire 的目标不是"能对话就行"，而是**成为 gRPC 生态里的一等公民**——一个标准的 grpc-java 客户端要能直接调 wire 服务端，`grpcurl` 要能探针它，反之亦然。

要"对等"，就没有"包一层 grpc-java 偷个懒"的余地：那样等于把整个 gRPC 运行时请进来当依赖，既违背 Jaws"核心可从头读到尾"的定位，也拿不到对底层每个约定的掌控。所以 wire 走的是硬路——**把 gRPC 在 HTTP/2 之上的每一条约定亲手实现**：帧格式、`:path` 头、`grpc-status` trailer、deadline、keepalive、health、reflection。代价是要啃很多细节，收益是这份实现本身就是 gRPC 线格式的说明书。

## 2. gRPC 线格式速览（wire 实现的靶子）

gRPC = HTTP/2 + 一层极薄的约定。wire 严格对齐这几点：

- **传输**：HTTP/2，每条 RPC 是一个 stream（一元/流式都是），多路复用同一条连接。
- **请求头**：`:method=POST`、`:path=/{package.Service}/{Method}`、`:scheme`、`content-type: application/grpc[+proto]`、可选 `grpc-timeout`。
- **消息帧**：每个 gRPC 消息在 DATA 帧里再套一层 **5 字节前缀**——1 字节压缩标志 + 4 字节大端长度，后接 payload。单请求形态（unary/server-streaming）把 END_STREAM 折进唯一那条 DATA 帧——HEADERS + DATA(END_STREAM) 两帧收发，与 grpc-java 同形，不再多发一条空 DATA。
- **响应状态**：成功/失败都靠 **HTTP/2 trailer** 携带 `grpc-status`（0–16）、`grpc-message`，富错误再加 `grpc-status-details-bin`。

## 3. 帧编解码：`WireFrameCodec`

那 5 字节长度前缀帧是 gRPC 区别于裸 protobuf-over-HTTP2 的关键，`WireFrameCodec` 逐字节实现：

```java
// wire/WireFrameCodec.java —— [1 字节 compressed-flag][4 字节大端 length][payload]
buf.writeByte(compressed ? WireConstants.COMPRESSED : WireConstants.NOT_COMPRESSED);
buf.writeInt(payload.length);
buf.writeBytes(payload);
```

解码侧 `tryExtractFrame(accumulator)` 负责处理 TCP/H2 的**粘包与半帧**：不足 `GRPC_HEADER_SIZE(=5) + length` 就攒着，凑齐才 `readRetainedSlice` 切出来——零拷贝，和 jaws 二进制协议的 body 处理同一套路。压缩标志为真时按本流协商出的 `grpc-encoding` 解码器解压，与 §7 的压缩协商联动。

## 4. 状态码与富错误：`grpc-status` + `grpc-status-details-bin`

gRPC 用 trailer 而非 HTTP 状态码表达业务结果。wire 把 **0–16 全部 17 个码**都实现了（`WireConstants`：`STATUS_OK=0 … STATUS_UNAUTHENTICATED=16`），并做双向映射：

- **异常 → 码**：`WireStatus.fromThrowable` 把 Jaws 异常族翻译成 grpc 码（超时→`DEADLINE_EXCEEDED(4)`、连接失败→`UNAVAILABLE(14)`、业务异常→`UNKNOWN(2)` 等）。
- **码 → 调用方**：服务端在 `WireStreamServerHandler.sendTrailers` 写 `grpc-status`/`grpc-message`；非 OK 时再写 `grpc-status-details-bin`——值是 **base64 编码的 `google.rpc.Status` protobuf**（可携带任意 `Any` 详情）。客户端 `WireStatus` 把它解回、抛 `WireStatusException`，让调用方拿到结构化富错误。

`google.rpc.Status` 这类 protobuf 生成物被隔离在 `jaws-wire-proto` 模块（见 §9），不污染 wire 主模块。

## 5. 四种调用类型：一元 + 三种流式

`WireMethodHandler.MethodType` 覆盖 gRPC 全部四种：`UNARY`、`SERVER_STREAM`、`CLIENT_STREAM`、`BIDIRECTIONAL`。`WireHandlerRegistry` 按 `/{service}/{method}` 路径路由到方法，`WireCallDispatcher` 分派。流式的收发复用 `jaws-stream-api` 的 **push-only** `StreamObserver`/`StreamSource`——和 jaws 协议走同一份流式契约（为什么不做手动 `request(n)`，见 architecture §2 与 codec 相关讨论：对齐 gRPC 默认 auto-request，流控交给 Netty H2 层）。

## 6. 名字解析与连接管理：`ManagedChannel` / `NameResolver`

gRPC 把"从一个 target 字符串解析出地址列表"抽象成 `NameResolver`，wire 照做了同构抽象，但不引 grpc-java：

- `ManagedChannel.Builder.target("dns:///host:port")` → `DnsNameResolver`；
- `addAddress(...)` → `PassthroughNameResolver`（等价 grpc 的 `passthrough:///`）；
- `nameResolver(...)` → 自定义。

`NameResolver.start(Listener)` 推送地址列表，`ManagedChannel` 据此**对每个地址 reconcile 出一个 `WireClient`**，再按 `LoadBalancePolicy` 选：**`ROUND_ROBIN`（轮转 + 建流失败时换下一个地址）** 或 **`PICK_FIRST`（粘住第一个）**。注意这条切换只覆盖同步抛出——`WireClient.request()` 返回的是 future，对端以 `grpc-status` 表达的失败不经过这个循环（见 §13）。

## 7. 运维语义：deadline / 压缩 / keepalive / GOAWAY / retry

wire 把 gRPC 的运维约定逐条补齐，这也是"能不能上生产对接"的分水岭：

- **deadline**：走 `grpc-timeout` header，服务端解析成绝对 `deadlineMs`，流式每帧吐出前检 `isDeadlineExceeded()`，超了直接 `sendTrailers(DEADLINE_EXCEEDED)`。
- **压缩**：`CompressorRegistry`（出站选）+ `DecompressorRegistry`（入站解 + 决定 advertise）两张表驱动，内置 identity 与 gzip（`java.util.zip`，零外部依赖），注册一个 `Codec` 即可按名字启用第三种编码。入站未知编码 → `UNIMPLEMENTED`，出站未被客户端广告 → 降级 identity；响应头恒写 `grpc-encoding`（对齐 grpc-java 的 "Always put compressor, even if it's identity"），请求头只在真压缩时才写。
- **keepalive（gRFC A8 服务端守卫）**：`WireKeepaliveHandler` 实现 gRPC 的"ping 过快"惩罚——PING 间隔小于许可值累计 strike，超过 `MAX_PING_STRIKES`（默认 **2**）才发 `GOAWAY` 带 `too_many_pings`，**不是第一次违规就踢**（和 grpc-java 服务端一致）。客户端 `WireClientKeepaliveHandler` 镜像 grpc-java 的 `KeepAliveManager` 状态机，无 ACK 即断连重连。
- **GOAWAY**：`WireGoAwayHandler` 收到后置 IDLE、关连接、立即重连。
- **RST 透传**：对端发来的 `RST_STREAM` 经 `Http2MultiplexHandler` 以 `Http2ResetFrame`
  user event 抵达流 pipeline，客户端两个响应 handler 据此**立即**以映射状态失败调用
  （`WireStatus.fromHttp2Error`：CANCEL→CANCELED、REFUSED_STREAM→UNAVAILABLE、
  ENHANCE_YOUR_CALM→RESOURCE_EXHAUSTED、余 INTERNAL），不再等满请求超时——
  grpc-java 服务端业务侧取消调用时，wire 客户端秒级拿到结果。
- **流窗口**：两端广告 `SETTINGS_INITIAL_WINDOW_SIZE=8MiB`（对齐 Dubbo TripleConfig；
  协议默认 64KiB 在大负载/高 RTT 下限吞吐），SETTINGS 交换后双向生效。
- **retry**：`WireRetryPolicy` 指数退避 + 抖动，且**仅 `UNAVAILABLE` 可重试**（`WireStatus.isRetryable`）。

## 8. health 与 reflection：让 grpcurl 免 proto 直连

两个标准 gRPC 服务让 wire 能被通用工具直接操作：

- **`WireHealthService`** 实现 `grpc.health.v1.Health`（Check/Watch），`WireServer` 自动挂载。两种模式共用**同一组 handler**：Direct API 模式注册进调用方的 `WireHandlerRegistry`，管线模式注册进服务器自己的内建 registry（`WireServer.java:130-137`），命中内建路径时由 `WireCallDispatcher.dispatcherFor` 改派给 registry 分发器（`WireStreamServerHandler.java:298-303`）——所以 Check/Watch 两模式行为一致，反射也都宣告它。注意 `Check` 只认显式登记过状态的服务名，未登记的按协议返回 NOT_FOUND；探整体状态用空串（实测 `grpcurl -d '{"service":""}' ... Health/Check` → `SERVING`）。
- **`WireReflectionService`** 实现 `grpc.reflection.v1.ServerReflection`，支持 `list services` / `FileContaining*`——**这正是 `grpcurl` 不挂 `.proto` 文件也能调用 wire 服务的原因**。实测管线模式 `grpcurl -plaintext host:port list` 只回业务服务名（如 `calculator.Calculator`、`greeter.Greeter`），不含 `grpc.*` 内建。

## 9. 桥接 core：wire 只是 Jaws 眼里的"又一种 Protocol"

`WireProtocol` 直接 `@Extension("wire") extends AbstractProtocol`（即实现 core 的 `org.hongxi.jaws.rpc.Protocol` 体系）。`createExporter → WireExporter`（经 `TransportFactory` 建 `WireServer`）、`createReference → WireReference`（委托 raw 客户端 `WireClient`；`ManagedChannel` 是 Direct API 的门面，不在这条链路上）。服务端派发由 `WireCallDispatcher`（一个 sealed interface）的两种策略承担：`WireCallDispatcher.HandlerCallDispatcher` 走强类型 `Message` 的 Direct API，`WireCallDispatcher.ProviderCallDispatcher` 桥到 Jaws 自己的 `MessageHandler` 管道（raw bytes）。**关键点**：wire 不另起编程模型，而是伪装成 core 眼里的普通 Protocol，于是注册中心、负载均衡、Filter 链、动态配置这些上层能力**全部白嫖**——这就是"对外咬合"能低成本落地的结构性原因。

拦截器也已重做成 grpc-java 式异步链，且**服务端与客户端不对称**：服务端 `WireServerInterceptor` 支持全部四种调用类型，客户端 `WireClientInterceptor` 目前只拦一元；链在 `WireClient` 里逐层包 `ForwardingClientCall`，Builder 方法是 `intercept(...)`。

## 10. `jaws-wire-proto`：protoc 生成码为什么单独成模块

`jaws-wire-proto` 里几乎全是 protoc 生成的 Java（`grpc/health`、`grpc/reflection`、`google/rpc/Status`），且是**手动 checked-in 进仓库**的——该模块 pom 里没有 protobuf-maven-plugin、没有 protoc 依赖。这是刻意设计，不是没配好构建：

- **离线可复现**：任何环境 clone 下来就能编译，不必装 protoc、不依赖插件联网拉取。
- **依赖隔离**：把 `google.rpc.*` 这类第三方 protobuf 依赖关在专门模块，不污染 `jaws-wire` 主模块。

所以别提"改成 protobuf-maven-plugin 每次重生成"——那会破坏"离线可复现"这个前提。

## 11. 反验铁律：自测全绿 ≠ 协议互通

最后一条，也是 wire 存在的一条纪律：**`jaws-to-jaws` 自测全绿并不代表和 gRPC 真互通**。两端同源时，双命名体系的问题会被同一套反射口径互相掩盖，跑再多遍也测不出来。所以 wire 的兼容性必须**从对面打过来**——用你不控制的第三方验证。`run-sample.sh interop` 就是干这个的：grpc-java 调 Jaws-wire、Jaws-wire 调真 gRPC、走 `ManagedChannel`、验 keepalive，四个方向都通，才算"对等 gRPC"。`grpcurl` 直连（靠 §8 的 reflection）则是最低成本的一路反验。用它时**每条命令都要带超时**：wire 服务端把响应发完后流不会走到 grpcurl 认可的收尾，`grpcurl` 打印结果（甚至打印错误）都不自行退出——实测 unary、server-stream、`list`、以及一次"服务不存在"的错误响应全都挂到被 kill 为止。所以判据取"响应载荷对不对"，别等它自己结束。

## 12. 性能基准：与 grpc-java 的双向对照（`bench-grpc`）

`run-sample.sh bench-grpc` 用一个 grpc-java 客户端打两种服务端：`SERVER=wire`（互操作
反验）与 `SERVER=grpc`（纯 grpc-java netty 服务端参照）。同一负载（`hello(String)`）、
20 线程、WARMUP=5s、DURATION=40s、分进程长驻、各轮 0 错误：

| 客户端 → 服务端 | QPS | 说明 |
|------|-----|------|
| grpc-java → wire 服务端（互操作） | 103,851 / 105,544 / 105,506 | 均值 **104,967**，第三方客户端反验口径 |
| wire 客户端 → wire 服务端（管线消费端） | 87,333 / 87,466 / 87,434 | 均值 87,411 |
| wire 客户端 → wire 服务端（Direct API 消费端） | 90,323 / 91,810 / 90,915 | 均值 **91,016**，绕过 ReferenceConfig 代理层 |
| grpc-java → grpc-java（正统 gRPC 参照） | 66,160 / 63,516 | 均值 64,838；换有界业务池持平 |

两个方向都值得读。**互操作 10.5 万**说明 wire 服务端在第三方客户端全文压下仍高于
正统 grpc-java 服务端（6.5 万）——grpc-java 服务端的开销大头在每 RPC 的回调穿越层
（`JumpToApplicationThread` + `SerializingExecutor` 两跳线程，`directExecutor` 对照
实验 64.8k → 92.9k）。**消费端两档 87.4k → 91k** 则量化了 wire 客户端代理层约 3.6k
的开销。与 grpc-java 客户端之间剩余 ~10k 的差距经 JFR 双向采样归因：头路径字符串
比较（已用 AsciiString 常量化消除，热点退出 top 榜）与 child-channel 架构税
（netty `Http2MultiplexHandler` 底座的 pipeline/Promise 固有成本，记档接受）。
完整数据与诊断表见 [benchmark.md](benchmark.md) 的 9/24 节。

## 13. 能力面清单：管线模式 vs Direct API 模式

wire 有两副面孔。**管线模式**走 core 的配置与协议链（`ServiceConfig`/`ReferenceConfig` → `Protocol` SPI → `WireExporter`/`WireReference`，服务发现由注册中心下发的 URL 驱动）；**Direct API 模式**走 grpc-java 形状的那套门面（客户端 `ManagedChannel` + `target`/`NameResolver`，服务端手搭 `WireServer` + `WireHandlerRegistry`）。两副面孔共用同一个 `WireStreamServerHandler` 和同一个 `WireClient`，所以 §3–§5、§7 的线格式能力两边一致，**差异全部集中在门面层**。

边界先立住：`NameResolver.java:13-15` 自陈 "This lives entirely on the raw `WireClient`/`ManagedChannel` path. The Jaws RPC path performs discovery through the `Registry` layer"。所以**调一个不在 jaws 注册中心里的 gRPC 服务，只有 Direct API 一条路**；反过来，管线模式白嫖注册中心与集群治理，但拿不到下面第一张表里的东西。

**只在 Direct API 侧**

| 能力 | 锚点 |
| --- | --- |
| `NameResolver` / `DnsNameResolver`（30s 重解析）/ `Passthrough` | `ManagedChannel.java:960-987`、`DnsNameResolver.java:57-104` |
| 客户端 LB（`round_robin` / `pick_first`）与地址级 failover | `LoadBalancerRegistry.java:23`、`ManagedChannel.java:619-657` |
| 每调用 `WireCallOptions`（deadline、compressor 覆盖） | `WireCallOptions.java:34-50` → `WireClient.java:810-845`；`WireReference.java:58-95` 一律走默认 |
| 客户端拦截器、自定义压缩/解压注册表、`ClientStreamTracer` | 只有 `ManagedChannel.java:585-592` 装配；管线侧 `WireTransportFactory.java:48` 是裸 `new WireClient(url)` |
| 连通性状态机（`getState` / `notifyWhenStateChanged`）与通道排水 | `ManagedChannel.java:266-431` |
| 精确 `/{service}/{method}` 路由，头部阶段即回 NOT_FOUND | `WireHandlerRegistry.java:37-40`；管线 `resolvePath` 恒 true（`WireCallDispatcher.java:600-605`），靠方法名回退找 provider，服务名/group/version 不参与匹配 |
| 服务端把取消暴露给业务（`isCancelled()`） | `WireCallDispatcher.java:547-550`（两模式的业务 handler 都收不到取消通知） |

**只在管线侧**

| 能力 | 锚点 |
| --- | --- |
| 注册中心注册与目录刷新 | `ServiceConfig.java:243-256`、`ConsumerCoordinator.java:109-121` |
| 集群容错（failover/failfast/failsafe）、Router 链、Filter 链、RpcContext attachment 自动注入 | `cluster/support/*`、`proxy/ReferenceInvoker.java:105-110,168-173` |
| 6 种负载均衡策略（random / roundRobin / leastActive / leastLoad / adaptive / consistentHash） | `META-INF/services/org.hongxi.jaws.cluster.LoadBalance` |
| 服务端 unary 的**主动超时**：阻塞型 handler 也会被计时打断 | `WireCallDispatcher.java:730` 的 `future.orTimeout`；Direct 侧只在解码前查一次 `isDeadlineExceeded()`（`:305-308`） |

**两边都有，但默认值或配置面不同**

- 超时：管线 `requestTimeout` 默认 **1000ms**、`connectTimeout` **1000ms**（`UrlParam.java:94-95`），Direct 是 **5000ms** / **3000ms**（`ManagedChannel.java:688-689`）。同一份业务代码换门面，超时表现就不同。
- 重试：两侧默认都是 `maxAttempts=2`（`UrlParam.java:165` / `ManagedChannel.java:1014`），关闭方式不同（URL 参数 vs `retryDisabled()`）。
- 传输旋钮：管线有强类型的 `compression` / `ssl*` / 线程池字段（`ProtocolConfig.java`），其余 wire 参数要塞 `parameters`；Direct 侧全靠自己造 URL，但参数名与默认值与管线**逐字相同**。
- 反射内容：Direct 列 handler registry 里的路径服务名，管线列接口反推的 proto 全名（`WireServer.java:275-313`）。

**两模式共同的结构性事实**（不是差异，但常被当成差异来问）

- 同一 `host:port` 只会有一个 server 实例：`AbstractTransportFactory.createServer` 命中即复用，**新传入的 handler 被丢弃**（`:33-51`）。因此管线侧一个端口只有一个 `WireMessageHandler`（`WireExporter.java:34-48`）：它既持有该端口的 provider 注册表（内含 `ProviderMessageHandler`），也持有**每个**导出服务的 protobuf 元数据，第二个及以后的服务向同一个 handler 注册、注销时只摘自己那份（`WireMessageHandler.java:58`、`:74`）。方法归属先按 path 里的 proto 服务名精确匹配，命中不了再退回方法名 join——因为 gRPC 调用方报 proto 服务名，而 jaws 调用方报 Java 接口名。
- 上一那条精确匹配只在 protobuf 转换这一层成立：provider 侧仍按方法名回退取**第一个**声明者（`AbstractRequestHandler.java:56`、`:126`），所以两个服务声明同名 Java 方法时，provider 归属是模糊的。
- Direct 的地址级 failover 只捕获同步抛出（`ManagedChannel.java:626-632`），而 `WireClient.request()` 返回 future（`:194-222`）：建流期失败会换后端，对端回了 `UNAVAILABLE` 不会。
- 服务端拦截器与 Filter 链是**分工**而非重复：`WireServerInterceptor` 只作用于 Direct API 的 `WireHandlerRegistry`，管线模式用 core 的 Filter 链（`WireServerInterceptor.java:35-36` 已写明）。

## 小结

wire 的价值有两层：一层是**能力**——Jaws 因此能进 gRPC 生态当一等公民；另一层是**认知**——它逼着把 gRPC 线格式的每一条约定都实现并读懂，于是这个模块本身成了"gRPC over HTTP/2 到底规定了什么"的可读参考。它和 jaws 二进制协议是同一套骨架（core 六层）之上的两种对外齿形：一个对等 gRPC，一个对等 Dubbo。

> 源码：`jaws-wire`（约 1.0 万行）+ `jaws-wire-proto`（生成码）。运行：`./run-sample.sh wire`（直连，兼容 grpcurl）、`./run-sample.sh interop`（grpc-java ↔ jaws-wire 双向互操作）、`./run-sample.sh bench-wire` / `bench-grpc`（性能基准，含互操作口径与 gRPC 参照口径）。
