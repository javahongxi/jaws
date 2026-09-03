# Jaws 与 Dubbo 全方位能力对比

本文档从核心 RPC、服务注册与发现、流量治理、可观测性、协议支持、工程化生态、编解码性能、安全等八个维度，对 Jaws 与 Dubbo 进行系统性对比。

## 一、核心 RPC 能力

| 能力维度       | Jaws                                                   | Dubbo                                                      | 差距评估   |
|----------------|--------------------------------------------------------|------------------------------------------------------------|------------|
| **自定义协议** | 16 字节头，零拷贝编解码，flag 嵌入 serializationId     | 16 字节头，零拷贝编解码，flag 嵌入 serializationId         | **持平**   |
| **序列化**     | fastjson2 / hessian2 / protostuff（3 种）              | hessian2 / protobuf / fastjson2 / kryo / fst 等（10+ 种）  | 中等差距   |
| **同步调用**   | 支持                                                   | 支持                                                       | **持平**   |
| **异步调用**   | `CompletableFuture` 返回值，Provider 端原生异步        | `CompletableFuture` + `Async` 后缀接口 + `RpcContext` 异步 | **持平**   |
| **泛化调用**   | `GenericService.$invoke()`                             | `GenericService.$invoke()`                                 | **持平**   |
| **Injvm 协议** | 支持 JVM 内直调                                        | 支持                                                       | **持平**   |
| **单向调用**   | 不支持                                                 | flag 中有 TWOWAY 位，支持 oneway                           | **有差距** |
| **流式调用**   | 支持 Server Streaming（HTTP/2 传输，`Flow.Publisher`） | 支持 client/server/bidirectional 三种流式模式              | 中等差距   |

## 二、服务注册与发现

| 能力维度                     | Jaws                        | Dubbo                                    | 差距评估   |
|------------------------------|-----------------------------|------------------------------------------|------------|
| **ZooKeeper**                | 支持（Curator）             | 支持                                     | **持平**   |
| **Nacos**                    | 支持                        | 支持                                     | **持平**   |
| **Redis**                    | 不支持                      | 支持                                     | 中等差距   |
| **Consul**                   | 不支持                      | 支持                                     | 中等差距   |
| **Kubernetes**               | 不支持                      | 支持（Native K8s Service）               | 中等差距   |
| **多注册中心**               | 支持同时注册/发现到多个中心 | 支持                                     | **持平**   |
| **per-service 指定注册中心** | 不支持（全局统一）          | 支持（`@DubboService(registryIds=...)`） | **有差距** |
| **服务分组/版本**            | group + version             | group + version                          | **持平**   |
| **元数据服务**               | 不支持（仅 URL 参数透传）   | 独立的 MetadataReport 抽象               | 中等差距   |
| **应用级服务发现**           | 不支持（接口级）            | 支持应用级 + 接口级双模型                | **差距大** |

### 多注册中心实现细节

Jaws 已具备多注册中心能力：

- **Provider 端**：`ServiceConfig.register()` 遍历所有 `registryUrls` 逐个注册
- **Consumer 端**：`RegistryDirectory` 用 `registryReferences` 按注册中心分组管理，独立订阅、独立通知，最终合并统一 reference 列表
- **配置方式**：通过 `AbstractInterfaceConfig.setRegistries(List<RegistryConfig>)` 配置多个注册中心

核心差距在于：注册中心列表是全局的，所有服务共享同一组注册中心，不支持 per-service 指定注册中心。

## 三、流量治理

| 能力维度              | Jaws                                                                                     | Dubbo                                                        | 差距评估   |
|-----------------------|------------------------------------------------------------------------------------------|--------------------------------------------------------------|------------|
| **负载均衡**          | random / roundRobin / leastActive / leastLoad / adaptive / consistentHash（6 种） | 同左（p2c 等）                                               | **持平**   |
| **高可用容错**        | failover / failfast（2 种）                                                             | failover / failfast / failback / forking / available（5 种） | 小幅差距   |
| **路由链**            | Router SPI + TagRouter + DynamicConfigRouter（IP/Group/Tag 规则）                        | Router SPI + 条件路由 / 标签路由 / 脚本路由                  | 小幅差距   |
| **动态配置**          | 全局/服务级/方法级三层热更新                                                             | 同左 + 更丰富的配置中心集成                                  | **持平**   |
| **标签路由/灰度发布** | TagRouter + provider tag + consumer attachment                                           | TagRouter + 条件路由                                         | **持平**   |
| **权重调节**          | 动态路由权重                                                                             | 动态权重 + 标签路由                                          | **持平**   |
| **限流/降级**         | 不支持（仅线程池过载保护）                                                               | 支持限流、降级、熔断（Sentinel 集成）                        | **差距大** |

### 标签路由实现

Jaws 已实现标签路由能力：

- **Provider 端**：`@JawsService(tag = "gray")` 或配置 `jaws.service.tag=gray`，tag 写入 URL 参数注册到注册中心
- **Consumer 端**：`RpcContext.getContext().setRpcAttachment("tag", "gray")` 指定目标标签
- **TagRouter**：从 `request.getAttachment("tag")` 取标签，过滤出匹配的 providers；无匹配时 fallback 到全部候选
- **DynamicConfigRouter**：支持 `tag=gray` 规则维度，可从配置中心动态下发路由规则

## 四、可观测性

| 能力维度       | Jaws                             | Dubbo                                        | 差距评估 |
|----------------|----------------------------------|----------------------------------------------|----------|
| **Metrics**    | Micrometer 指标采集              | Micrometer + Prometheus / 自定义             | **持平** |
| **Tracing**    | OpenTelemetry + W3C TraceContext | OpenTelemetry + Zipkin / Jaeger / SkyWalking | **持平** |
| **Access Log** | AccessLogFilter                  | 同                                           | **持平** |
| **日志关联**   | traceId/spanId 注入 MDC          | 同                                           | **持平** |

## 五、协议与多协议支持

| 能力维度           | Jaws                                                 | Dubbo                                                 | 差距评估        |
|--------------------|------------------------------------------------------|-------------------------------------------------------|-----------------|
| **自有协议**       | jaws（二进制）                                       | dubbo（二进制）                                       | **持平**        |
| **传输层**         | Netty（默认）/ HTTP/2（可选，基于 Netty 自研）       | Netty                                                 | **Jaws 更灵活** |
| **应用层协议**     | jaws（二进制）/ wire（gRPC 线格式，protobuf 序列化） | dubbo（二进制）/ Triple（兼容 gRPC，IDL + 流式）      | **持平**        |
| **REST/HTTP**      | REST 桥接（Servlet）                                 | 原生 rest 协议（JAX-RS）                              | **差距较大**    |
| **Injvm**          | 支持                                                 | 支持                                                  | **持平**        |
| **多协议同时暴露** | 支持 jaws / http2 / wire 三种协议（需不同端口）      | 支持同一端口多协议自动路由（Port Unification Server） | 小幅差距        |
| **MCP 桥接**       | 支持（Dubbo 无此能力）                               | 不支持                                                | **Jaws 领先**   |

### REST 协议架构差异

Dubbo 的 REST 是独立的一等公民协议（`rest://`），底层嵌入 Servlet 容器，支持 JAX-RS 注解（`@Path`/`@GET`/`@POST`），可自定义 URL 路径、HTTP 方法、参数位置。

Jaws 的 REST 是桥接层，在已有 jaws RPC 服务之上套 HTTP 入口，URL 结构自动生成固定为 `/rest/invoke/{interface}/{method}`，无 JAX-RS 注解支持。

| 维度     | Dubbo REST 协议                    | Jaws REST 桥接                           |
|----------|------------------------------------|------------------------------------------|
| 定位     | 一等公民协议，`rest://` 独立暴露   | 桥接层，底层仍是 jaws 协议               |
| 注解     | JAX-RS（`@Path`/`@GET`/`@POST`）   | 无注解，自动根据 RPC 方法生成            |
| 参数绑定 | `@PathParam`/`@QueryParam`/`@Body` | 统一 JSON body                           |
| 独立部署 | 可独立监听端口，不依赖 RPC 协议    | 依赖 Spring Boot 内嵌 Servlet            |
| 灵活性   | 完全自定义 URL/Method/参数位置     | 固定 `/rest/invoke/{interface}/{method}` |

## 六、工程化与生态

| 能力维度                | Jaws                                                             | Dubbo                                          | 差距评估     |
|-------------------------|------------------------------------------------------------------|------------------------------------------------|--------------|
| **Spring Boot Starter** | `@EnableJaws` + `@JawsService` / `@JawsReference`                | `@DubboService` / `@DubboReference` + 自动配置 | **持平**     |
| **SPI 扩展**            | 11 个 SPI 扩展点                                                 | 100+ 个 SPI 扩展点                             | **差距大**   |
| **Filter 链**           | 2 个内置（AccessLog + TokenAuth）+ 2 个扩展（Metrics + Tracing） | 20+ 个内置 Filter                              | **差距较大** |
| **代码生成**            | 支持 protobuf IDL（wire 模块，.proto 定义服务接口）              | 支持 IDL 代码生成（protobuf/thrift）           | 小幅差距     |
| **Admin 控制台**        | 不支持                                                           | Dubbo Admin（服务治理、测试、监控）            | **差距大**   |
| **文档与社区**          | 个人项目                                                         | 庞大社区 + Apache 顶级项目                     | **差距极大** |

## 七、编解码与性能

| 能力维度       | Jaws                                                        | Dubbo | 差距评估 |
|----------------|-------------------------------------------------------------|-------|----------|
| **编码效率**   | 预留 header + body 直写 ByteBuf + 回填，0 次额外分配        | 同    | **持平** |
| **解码效率**   | retainedSlice + ByteBufInputStream 零拷贝                   | 同    | **持平** |
| **流式序列化** | ObjectOutput/ObjectInput 流式 API                           | 同    | **持平** |
| **心跳**       | 双向心跳，flag event 位标记                                 | 同    | **持平** |
| **OOM 保护**   | NettyDecoder maxContentLength + 线程池拒绝                  | 同    | **持平** |
| **连接预热**   | warmup 线性加权                                             | 同    | **持平** |
| **优雅停机**   | 四阶段（stopAccept → awaitInactive → unregister → destroy） | 同    | **持平** |

## 八、安全

| 能力维度       | Jaws                  | Dubbo    | 差距评估 |
|----------------|-----------------------|----------|----------|
| **Token 鉴权** | TokenAuthFilter       | 同       | **持平** |
| **mTLS**       | 依赖 Netty SslContext | 原生支持 | 小幅差距 |
| **RBAC/权限**  | 不支持                | 支持     | 中等差距 |

## 九、差距最大的 Top 5 领域

1. **序列化协议丰富度** — Jaws 3 种（fastjson2/hessian2/protostuff），Dubbo 10+ 种（含 protobuf、kryo 等更多高性能选项）
2. **流量治理深度** — 缺少限流、熔断、降级能力（Dubbo 集成 Sentinel/Resilience4j）
3. **流式调用完备性** — 仅支持 Server Streaming，缺少 Client Streaming 与 Bidirectional Streaming（Dubbo Triple 三种流式模式均已实现）
4. **应用级服务发现** — 仅支持接口级发现，Dubbo 已支持应用级 + 接口级双模型，大规模场景下内存和推送效率差距明显
5. **生态与运维工具** — 缺少 Admin 控制台、缺少 IDL 代码生成、SPI 扩展点数量有限

## 十、Jaws 的差异化点

- **MCP 桥接（领先）** — Dubbo 目前没有此能力，Jaws 可将 RPC 服务直接暴露为 AI Agent 可调用的 MCP Tools
- **gRPC 线格式兼容（新能力）** — `jaws-wire` 模块实现标准 gRPC 线格式（5 字节长度前缀帧 + trailers 状态码），通过 WireProtocol 完整支持注册中心/负载均衡/Filter 链，兼容 grpcurl 等标准 gRPC 工具，无 grpc-java 依赖
- **HTTP/2 传输轻量可插拔（设计取舍）** — 通过 `TransportFactory` SPI 零侵入接入基于 Netty `Http2FrameCodec` + `Http2MultiplexHandler` 自研的 HTTP/2 传输，无 grpc-java/protobuf 依赖，复用 Jaws 序列化体系，可获得多路复用、流控、网关穿透与 Server Streaming 能力
- **编解码设计简洁性** — JawsCodec 分层清晰，Dubbo 的继承体系更复杂
- **独立 version 字段** — header 层即可做版本校验，Dubbo 需解析 body

## 总结

Jaws 在**核心 RPC 链路**（协议、编解码、零拷贝、心跳、优雅停机、异步调用）上已与 Dubbo **基本持平**，在**标签路由/灰度发布**、**多注册中心**、**HTTP/2 传输（含 Server Streaming）**、**gRPC 线格式兼容**能力上已补齐。通过 `jaws-wire` 模块支持标准 gRPC 线格式与 protobuf 序列化，兼容 grpcurl 等标准工具，且无 grpc-java 依赖。但在**生态广度**（序列化种类、注册中心种类、运维工具）和**流量治理深度**（限流熔断）上差距较大。这些差距本质上是 Dubbo 多年社区积累的结果。
