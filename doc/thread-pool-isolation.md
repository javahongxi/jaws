# jaws 协议接口级线程池隔离设计（方案记录，未实施）

> **状态**：本文是设计方案记录，代码保持现状未改动。现状是三条传输腿共用一个按 `host:port` 建立的 `serverExecutor`（见 [AbstractNettyServer.open](../jaws-core/src/main/java/org/hongxi/jaws/transport/AbstractNettyServer.java)），不做接口级拆分。本文只描述"若要做接口级隔离，最佳落点与取舍"。
>
> **适用范围**：仅针对 **jaws 二进制协议**（`JawsCodec` 走的 Netty / HTTP/2 传输腿）在**同一 provider 内部**的派发选池，与 `jaws-wire`（与 gRPC 线格式互通的那套协议）无关，也不涉及任何跨协议互操作。

## 1. 动机与卡点

多服务共享一个业务线程池时，任接口的慢调用或突发都会占满整池、饿死其他接口——这是租户/QoS 隔离层面的诉求，与吞吐无关。jaws 当前不具备接口级隔离的能力，根因不是"建不了多个池"，而是**选池时机早于身份解析**：

| 事实 | 代码位置 | 后果 |
|---|---|---|
| 请求在 `channelRead` 就被投进共享池 | [NettyChannelHandler.channelRead L95-L105](../jaws-core/src/main/java/org/hongxi/jaws/transport/netty/NettyChannelHandler.java) | 投池时还不知道这条请求属于哪个接口 |
| 接口名要在全 decode 后才拿到 | [JawsCodec.decodeRequest L320-L336](../jaws-core/src/main/java/org/hongxi/jaws/transport/netty/JawsCodec.java) | 身份出现在业务线程上，晚于选池点 |
| 16 字节固定头无 service 标识 | [JawsCodec.writeHeader L302-L318](../jaws-core/src/main/java/org/hongxi/jaws/transport/netty/JawsCodec.java) | 事件循环上读头也拿不到接口 |

`encodeRequest` 把 `interfaceName` 写成 **body 的第一个字段**（[JawsCodec L213-L216](../jaws-core/src/main/java/org/hongxi/jaws/transport/netty/JawsCodec.java)）——身份已在 payload 里，只是当前在业务线程才解出来。这是本设计能落地的关键前提。

## 2. 三条路线与选型

| 方案 | 选池时机 | 事件循环代价 | jaws 协议改动 | 跳数 |
|---|---|---|---|---|
| A. 扩固定头加 `serviceId` | decode 前读头即知 | 0 | **改帧格式，需 VERSION 演进、编解码两端对齐** | 单跳 |
| B. 全 decode 后再选（照抄 Dubbo） | decode 后 | 反序列化搬上 event loop，**违背 jaws "事件循环只做帧解码 + 投池" 的纪律** | 不改帧格式 | 单跳但 I/O 变重 |
| C. **只窥探身份 + ExecutorManager 选池** | 读头 + body 首字段 | 仅一次定长 `readUTF` 窥探 | **不改帧格式、无需版本演进** | 单跳 |

Dubbo 3.3 能服务级隔离，是因为它默认在 I/O 线程把 body 全解成带 serviceName 的 `Invocation`，handler 再按服务选池（`IsolationExecutorRepository`，`getProviderKey` 以端口为粒度、服务级靠 `SERVICE_EXECUTOR` 注入）。jaws 刻意把重 decode 挪到业务线程保持事件循环精简，不能照搬 B。**故选 C**：不加重 I/O、不动协议、仍是单跳派发。

## 3. 方案 C 设计

### 3.1 身份窥探：NettyDecoder 产出带 interfaceName 的 DecodedFrame

[NettyDecoder](../jaws-core/src/main/java/org/hongxi/jaws/transport/netty/NettyDecoder.java) 现在已在事件循环上解析 16B 头并 `readRetainedSlice` 切出 body。扩展 `DecodedFrame` 携带 `interfaceName`：在切 body 后、投池前，对 body slice 做一次**序列化无关的定长 UTF 窥探**——各 `Serialization` 的 `ObjectOutput` 均遵循 modified-UTF-8（2 字节长度前缀 + 字节）写首个 `writeUTF`，故无需实例化 codec 即可读出首个字符串作为接口名。成本是每请求一次 `readUTF`，不触碰对象图、不反序列化参数。窥探失败（异常/截断）时 `interfaceName` 置空，回落共享池，绝不因选池逻辑抛错拒掉整条请求。

### 3.2 配置来源与注册：export 时推，派发时查

**参数定义复用 [UrlParam.Server](../jaws-core/src/main/java/org/hongxi/jaws/common/UrlParam.java) 的 `minWorkerThreads / maxWorkerThreads / workerQueueSize`（外加可选 `threadpool` 类型名）**，作用域从 server 级下沉到 interface 级；配置入口是 [ServiceConfig](../jaws-core/src/main/java/org/hongxi/jaws/config/ServiceConfig.java) / `InterfaceConfig`（`collectParams` 进导出 URL），物理上挂在每个 [AbstractProvider.url](../jaws-core/src/main/java/org/hongxi/jaws/rpc/AbstractProvider.java) 上。

取用方式**不让传输层现场翻 providers map**（那会把 `NettyChannelHandler` 和 provider 内部结构耦死），而是**导出时注册进一个 per-server 的 `ExecutorManager`**——这与 [JawsExporter](../jaws-core/src/main/java/org/hongxi/jaws/protocol/jaws/JawsExporter.java) 导出时已把 `interfaceClass` 登记进 `HttpServer/AdaptiveServer`（`addInterfaceClass`）同构：

```
export 阶段:  JawsExporter → server.executorManager().register(interfaceName, provider.getUrl())
派发阶段:     handler 拿到 peek 的 interfaceName
              → executor = executorManager.getOrCreate(interfaceName)
                  · 已注册参数 → computeIfAbsent 懒建该接口专用池
                  · 未注册     → 返回共享 serverExecutor（行为不变、零风险默认）
```

`ExecutorManager` 是选池的门面 SPI，`NettyChannelHandler` / `Http2StreamServerHandler` 只依赖它，不感知 providers map。**注意键是 `interfaceName` 而非完整 serviceKey**：group/version 在 attachments 里，全 decode 后才有，peek 阶段拿不到；既然定位是"接口级"，就按 `interfaceName` 建池（同接口不同 group/version 共用一池）。若将来要 group/version 粒度，属另一档需求，需挪到 decode 后二次路由（多一跳）。

### 3.3 派发选池：三条腿共用同一 ExecutorManager

把各 handler 的 `serverExecutor.execute(...)` 替换为 `executorManager.getOrCreate(frame.interfaceName()).execute(...)`。Netty 腿改 [NettyChannelHandler.channelRead L95-L105](../jaws-core/src/main/java/org/hongxi/jaws/transport/netty/NettyChannelHandler.java)，HTTP/2 腿改 [Http2StreamServerHandler](../jaws-core/src/main/java/org/hongxi/jaws/transport/http2/Http2StreamServerHandler.java) 的 `dispatch()`。**仍是"先投池、业务线程上再 decode body"的单跳模型**，身份只是提前在帧上带好了，事件循环不碰反序列化。

## 4. 一致性红线

分池后必须一并处理，否则引入回归：

| 关注点 | 现状 | 分池后要求 |
|---|---|---|
| **拒绝语义** | 池满回 `40002 SERVICE_REJECT`（[L106-L116](../jaws-core/src/main/java/org/hongxi/jaws/transport/netty/NettyChannelHandler.java)） | 每池各自 reject，错误文案带上是哪个 `interfaceName` 池满，否则失去定位 |
| **优雅停机** | 四阶段 unregister → stopAccept → drainInflight → destroy；`inflightRequests` / `accepting` 见 [AbstractNettyServer L39-L50](../jaws-core/src/main/java/org/hongxi/jaws/transport/AbstractNettyServer.java) | drain 与 shutdown 要覆盖所有接口池；`inflightRequests` 建议保持全局一份汇总，不拆成每池一个（易漏边界） |
| **ByteBuf 引用计数** | `retain/release` 见 [L97-L123](../jaws-core/src/main/java/org/hongxi/jaws/transport/netty/NettyChannelHandler.java) | 逻辑不变，仅执行线程换池，配对照旧 |
| **RpcContext** | 业务线程上 `init/destroy` 配对（[processRequest L151-L189](../jaws-core/src/main/java/org/hongxi/jaws/transport/netty/NettyChannelHandler.java)） | 仍是同一执行线程内配对，池切换不影响 ThreadLocal 生命周期 |

## 5. 可选热更层

静态来源是导出 URL。若要运行期不重启调某接口池大小，复用 jaws 已有的 `DynamicConfiguration` SPI（Harbor / Nacos / ZK 三腿同形，[ServiceNameMapping](../jaws-core/src/main/java/org/hongxi/jaws/metadata/ServiceNameMapping.java) 已用同一通道存 interface→app 映射，是现成先例）：`ExecutorManager` 订阅 `threadpool/<interfaceName>` 键，变更时 `setCorePoolSize/setMaximumPoolSize` 就地扩缩。**这是叠加层非必需**，第一版只做"URL 参数 + export 注册 + 懒建池 + 回落"即可。

## 6. 价值边界

1. **接口级 ≠ 方法级**：按 interface 分池解决不了同一接口内的读写争抢。jaws-store 的 `StoreService` 中 put/pull 同接口，接口级隔离会把读写落进同一池——那正是当初 §11 选择在 store 模块内加私有 `writeExecutor` 做**方法维度**隔离、而不动框架的原因（见 [jaws-store-benchmark.md §11](jaws-store-benchmark.md)）。本文的接口级隔离适合"多个互不信任服务挤在同一 provider"的场景。
2. **买的是隔离性与内存卫生，不是吞吐**。若瓶颈是锁或序列化墙（jaws-store 的 ~100–130k/s 写天花板由全局锁 + 序列化决定，与线程数无关），分池不提速，只做故障隔离。上框架级分池前，先确认瓶颈确实从"锁/序列化"转移到了"池争抢"。
3. **对照 RocketMQ**：RocketMQ 按请求类型拆 `sendMessageExecutor` / `pullMessageExecutor` 等近 14 个专用池，靠 header 里的 request code 单跳选池（等价本文方案 A）；jaws 不破坏协议帧，走方案 C 的身份窥探达到同样的"单跳选池"，是契合自身 I/O 纪律的取舍。

## 7. 实施落点清单（备查，本次不动代码）

- [NettyDecoder](../jaws-core/src/main/java/org/hongxi/jaws/transport/netty/NettyDecoder.java) / `DecodedFrame`：增 `interfaceName` 窥探字段
- [JawsCodec](../jaws-core/src/main/java/org/hongxi/jaws/transport/netty/JawsCodec.java)：暴露定长 UTF 首字段 peek 工具（帧格式不变）
- 新增 `ExecutorManager`（per-server 门面）+ 池类型/命名沿用现有 `EagerThreadPoolExecutor` + `DefaultThreadFactory`
- [AbstractNettyServer.open](../jaws-core/src/main/java/org/hongxi/jaws/transport/AbstractNettyServer.java)：建 `ExecutorManager`，shutdown 覆盖全池
- [NettyChannelHandler](../jaws-core/src/main/java/org/hongxi/jaws/transport/netty/NettyChannelHandler.java) / [Http2StreamServerHandler](../jaws-core/src/main/java/org/hongxi/jaws/transport/http2/Http2StreamServerHandler.java)：投池点改查 `ExecutorManager`，reject 文案带 interfaceName
- [JawsExporter](../jaws-core/src/main/java/org/hongxi/jaws/protocol/jaws/JawsExporter.java) / [ServiceConfig](../jaws-core/src/main/java/org/hongxi/jaws/config/ServiceConfig.java)：export 时注册 `interfaceName → URL 池参数`
- 可选：`DynamicConfiguration` 订阅 `threadpool/<interfaceName>` 做热更
