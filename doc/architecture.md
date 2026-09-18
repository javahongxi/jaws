# Jaws 架构总览：模块地图与六层骨架

> 这是读 Jaws 源码的**第一站**：先讲清 10 个 Maven 模块各自负责什么、`jaws-core` 内部怎么分层、以及贯穿全框架的几条设计主张。每个专题再深入哪一层，文末有文档地图。**模块边界就是设计取舍留下的物理证据**——看懂了切分，就看懂了它为什么"约 Dubbo 1/10 代码量"。

## 1. 定位与门面口径

Jaws 是一个**核心 2.9 万多行、可以从头读到尾**的轻量级 RPC 框架，用约 Dubbo 1/10 的代码量，把工业级 RPC 的核心机制走了一遍（多协议传输、序列化、注册发现、六种负载均衡、容错、路由、gRPC 线格式对等、HTTP/2 三种流式、自适应传输、全链路异步、优雅停机、可观测性），实测约 14 万 QPS。目标是做 **RPC 骨架的标杆**：每层薄到能读完，读完再去啃 Dubbo 会快很多。

**门面数字口径**（钝表述、防过时，与"约 Dubbo 1/10 / 约 14 万 QPS"同一套算法）：

- "核心 2.9 万多行" = `jaws-core` + `jaws-wire` + `jaws-stream-api` + `jaws-registry-nacos`/`jaws-registry-zookeeper` 两个客户端。
- **不计入**：`jaws-wire-proto`（protoc 生成码）、整个 `jaws-harbor`（服务端件，手码与内嵌生成码一并剔除）、`jaws-samples`、`jaws-spring-boot`、`jaws-extensions`。

## 2. 模块地图

按 `pom.xml` 的 `<modules>` 构建顺序，恰好是一条自底向上的依赖链：

```
jaws-stream-api → jaws-core → jaws-wire → jaws-wire-proto
     → jaws-registry-zookeeper / jaws-registry-nacos
     → jaws-spring-boot → jaws-extensions → jaws-harbor → jaws-samples
```

| 模块 | 手码规模 | 职责 | 层 |
|---|---|---|---|
| `jaws-stream-api` | ~95 行 | 流式收发的中立契约（push-only） | 契约 |
| `jaws-core` | ~20.5k | RPC 骨架六层 + 扩展机制 | 骨架 |
| `jaws-wire` | ~7.9k | 零 grpc-java 的 gRPC 线格式实现 | 对外协议 |
| `jaws-wire-proto` | 生成码 | protoc 生成物（health/reflection/rpc.Status）隔离 | 对外协议 |
| `jaws-registry-zookeeper` | ~670 | Registry SPI 的 ZooKeeper 实现（Curator） | 注册发现 |
| `jaws-registry-nacos` | ~490 | Registry SPI 的 Nacos 薄封装 | 注册发现 |
| `jaws-harbor` | ~4.6k 手码 | 兼容 nacos-client 的轻量注册中心（服务端） | 注册发现 |
| `jaws-spring-boot` | 聚合 + ~1.6k | 两个 starter：声明式接入 + 可观测装配 | 集成 |
| `jaws-extensions` | ~280 | 指标 / 链路追踪 Filter | 集成 |
| `jaws-samples` | 示例 | 一键实跑矩阵，含双向互操作与压测 | 验证 |

## 3. jaws-core 的六层骨架

`jaws-core` 的包目录本身就是分层图，从最底"收发字节"往上：

| 层 | 包 | 干什么 | 关键类 / 深入文档 |
|---|---|---|---|
| 传输 | `transport/{netty,http,http2,adaptive}` | 连接与字节收发；四种协议单端口自适应分诊 | `TransportResolver`、`ProtocolDetectionHandler`；[wire-grpc-compat](wire-grpc-compat.md)、[netty-performance](netty-performance.md) |
| 协议/序列化 | `protocol/jaws`、`serialization` | 16 字节二进制头、4 种序列化 SPI | `JawsCodec`；[codec-comparison](codec-comparison.md) |
| 代理 | `proxy` | 接口调用 → `Invocation`；泛化 | `JdkProxyFactory`、`GenericInvocationHandler`；[generic-invocation](generic-invocation.md) |
| 集群 | `cluster/{directory,router,loadbalance,support}` | 选址、路由链、6 种 LB、3 种容错、预热 | `AdaptiveLoadBalance`(P2C)、`RouterChain`；[warm-up](warm-up.md) |
| 注册发现 | `registry` | Registry SPI + 失败补偿 | `FailbackRegistry`；[registry-comparison](registry-comparison.md)、[harbor-vs-nacos](harbor-vs-nacos.md) |
| 配置/过滤器 | `config`、`filter` | 声明式编排、Filter 链、动态配置、优雅停机 | `ServiceConfig`、`FilterChainBuilder`；[dynamic-config](dynamic-config.md)、[graceful-shutdown](graceful-shutdown.md)、[observability](observability.md)、[token-auth](token-auth.md) |

贯穿各层的抽象在 `rpc` 包：`Protocol` / `Invoker` / `Invocation` / `Result`。全框架的异步是一根贯穿七层的 `CompletableFuture` 线，见 [async-full-chain](async-full-chain.md)。

## 4. 两种协议、三处"对外咬合"

Jaws 名字里的 "Jaws（咬合）" 有纵横两义：纵向是 core 仿 Dubbo 的分层管线（层间咬合），横向是**对外适配时自己单方面长成对方的齿形**——不是要求别人来接它。三处对外咬合：

- **wire ↔ gRPC**：`jaws-wire` 零 grpc-java 依赖实现 gRPC 线格式，与原生 gRPC、`grpcurl` 双向互通。→ [wire-grpc-compat](wire-grpc-compat.md)
- **harbor ↔ nacos-client**：`jaws-harbor` 服务端说 Nacos 2.x 的 naming gRPC 协议，真实 nacos-client 可直接把它当 Nacos 用。→ [harbor-vs-nacos](harbor-vs-nacos.md)
- **jaws 协议 ↔ Dubbo**：core 的分层、二进制头、优雅停机等对标 Dubbo，但用约 1/10 代码量复刻骨架。→ [dubbo-comparison](dubbo-comparison.md)

一条配套纪律：**`jaws-to-jaws` 自测全绿 ≠ 协议互通**（同源两端会互相掩盖命名体系问题），所以兼容性必须用第三方工具反向验证——`run-sample.sh interop`（grpc-java ↔ jaws-wire 双向）就是这条铁律的落地。

## 5. 四条贯穿全框架的设计主张

1. **抽象层包住多实现（ports/adapters）**：transport / serialization / registry / loadbalance 全做成可换实现的 SPI，稳定的是抽象与调用协议，可换的是底层框架。换 Netty↔HTTP/2、换 fastjson↔fury，上层不动。
2. **用标准，而非重造**：扩展机制 `ExtensionLoader` 复用 JDK 的 `META-INF/services/` 目录约定（但加载器自己写），**没有 Dubbo 那套 `@Adaptive` + IoC/AOP**；异步直接用 JDK `CompletableFuture`；注册中心用 Curator；可观测用 Micrometer。自研的力气留给 gRPC 线格式这种真正需要自己啃的地方。
3. **异步是一等公民，不是 API**：`MessageHandler.handleAsync`、`Filter.filter` 返回 `CompletableFuture`、Provider `invoke` 统一 Future、IO 线程 `whenComplete` 零阻塞——任何一层退回同步，全链路就在那儿断。→ [async-full-chain](async-full-chain.md)
4. **可读性优先、边界诚实**：每层薄到能读完；连"push-only 流式是取舍、protoc 生成码 checked-in 是特色、harbor 成员纯静态是边界、jaws 二进制协议无原生 TLS 是缺口"这些容易被当成缺陷的点，都写明理由或如实标注差距。

## 6. 文档地图

| 想深入 | 去这篇 |
|---|---|
| 全链路异步（七层） | [async-full-chain.md](async-full-chain.md) |
| gRPC 线格式与互通 | [wire-grpc-compat.md](wire-grpc-compat.md) |
| 编解码 / 协议头 | [codec-comparison.md](codec-comparison.md) |
| 内置注册中心 ↔ Nacos | [harbor-vs-nacos.md](harbor-vs-nacos.md) |
| 与 Dubbo 全维度对比 | [dubbo-comparison.md](dubbo-comparison.md) |
| 注册中心实现（ZK / Nacos） | [registry-comparison.md](registry-comparison.md) |
| 动态配置 / 优雅停机 / 预热 / 鉴权 / 可观测 / 泛化 | 对应同名文档 |
| 传输层性能 / 压测 | [netty-performance.md](netty-performance.md)、[benchmark.md](benchmark.md) |

> 源码：[github.com/javahongxi/jaws](https://github.com/javahongxi/jaws)（Apache-2.0，Java 17+）。
