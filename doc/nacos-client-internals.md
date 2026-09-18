# Nacos-Client 命名 gRPC 内部机制：harbor 的对面那半

> 这篇讲**真·nacos-client 怎么发命名 gRPC 请求**——它是 [harbor-vs-nacos.md](harbor-vs-nacos.md)（服务端字帖）的**对面**：字帖讲"harbor 服务端怎么做"，这篇讲"客户端期望服务端怎么回"。两者合起来，就是 harbor 兼容性要满足的**契约边界**。基线：本地 Nacos 主干源码（`~/github/nacos`）。引用只到类 / 概念 / 默认值，不钉行号。

## 0. 一图流：一次注册 / 订阅在客户端怎么发出去

```
NamingGrpcClientProxy.doRegisterService
  → new InstanceRequest(ns, svc, group, "registerInstance", instance)   // 动作在 body.type
  → requestToServer → RpcClient.request(Payload)                        // metadata.type="InstanceRequest"
      → GrpcClient 一元 stub Request.request(Payload) → Payload         // harbor 回 InstanceResponse
  → 失败/断连：NamingGrpcRedoService 标脏，RedoScheduledTask 重连后重发

订阅：doSubscribe → SubscribeServiceRequest(subscribe=true) → SubscribeServiceResponse(带 ServiceInfo)
推送：harbor 在 bidi 流上发 NotifySubscriberRequest → NamingPushRequestHandler.processServiceInfo → 回 NotifySubscriberResponse
```

## 1. 传输与协议底座

- **两个 service**（`api/.../nacos_grpc_service.proto`）：`Request.request(Payload) → Payload`（一元）与 `BiRequestStream.requestBiStream(stream Payload) → stream Payload`（全双工）。注册中心 RPC 面只有这两种形态。
- **信封 `Payload = Metadata{type, clientIp, headers} + google.protobuf.Any body`**。关键约定（`common/.../remote/client/grpc/GrpcUtils.java`）：
  - `metadata.type = request.getClass().getSimpleName()`——**是 Java 类简名**（如 `"InstanceRequest"`），不是动作名；
  - `Any` 只装 JSON 字节、**不带 typeUrl**，类型分派完全靠 `metadata.type`；
  - 反序列化走 `PayloadRegistry.getClassByType(metadata.type)`，不认识的 type 直接 `Unknown payload type` 报错。
  - **推论**：任何兼容服务端（含 harbor）必须**精确回显类简名**。
- **端口**：客户端连 `mainPort + rpcPortOffset`（gRPC 默认 +1000）。

## 2. 连接生命周期（客户端发 ↔ 期望服务端回）

| 阶段 | nacos-client 发 | 期望服务端回 | 备注 |
|---|---|---|---|
| 建连握手 | `ServerCheckRequest`（一元 future stub，超时 `serverCheckTimeOut` 默认 3s） | `ServerCheckResponse`，含 **connectionId**（必需）+ `supportAbilityNegotiation` | 拿到 connectionId 才算连上 |
| 打开 bidi | `ConnectionSetupRequest`（**在 bidi 流上**，带 clientVersion / labels / abilityTable / tenant） | 若协商：服务端推 `SetupAckRequest`，客户端回 `SetupAckResponse`；不协商则客户端 sleep 100ms 即过 | 服务端可省 SetupAck |
| 空闲保活 | 无活动超过 `connectionKeepAlive`（默认 **5s**）时发 `HealthCheckRequest`（超时 3s） | `HealthCheckResponse`（success） | 失败→UNHEALTHY→重连 |
| 服务端重定向 | — | `ConnectResetRequest`（带可选 serverIp/port） | 客户端回 `ConnectResetResponse` 并切服务器 |
| 服务端探活 | — | `ClientDetectionRequest` | 客户端回 `ClientDetectionResponse` |

- `lastActiveTimeStamp` 被**每次成功请求**和**每次服务端推送**刷新，不只是定时器。
- 别混淆两层保活：上面这条 5s 是**应用层 `HealthCheckRequest`**；channel 级 HTTP/2 keepalive（`GrpcClient` `keepAliveTime` 默认 6min）是另一回事。
- 代码位：`common/.../remote/client/RpcClient.java`（生命周期 + `handleServerRequest`）、`common/.../remote/client/grpc/GrpcClient.java`（`serverCheck` / `bindRequestStream` / TLS / 两个 stub）。

## 3. naming 操作 ↔ 请求类（两级分派）

`NamingGrpcClientProxy`（`client/.../naming/remote/gprc/`）所有调用走 `requestToServer(request, responseClass)`，成功判据 `ResponseCode.SUCCESS`（200）。

| 客户端方法 | 发（metadata.type） | JSON body 里的 type | 期望响应 |
|---|---|---|---|
| `doRegisterService` | `InstanceRequest` | `registerInstance` | `InstanceResponse` / `Response` |
| `doDeregisterService` | `InstanceRequest` | `deregisterInstance` | `Response` |
| `doBatchRegisterService` | `BatchInstanceRequest` | `batchRegisterInstance` | `BatchInstanceResponse` |
| `doSubscribe` | `SubscribeServiceRequest`（subscribe=true） | — | `SubscribeServiceResponse`（带 ServiceInfo） |
| `doUnsubscribe` | `SubscribeServiceRequest`（subscribe=false） | — | `SubscribeServiceResponse` |
| `queryService` | `ServiceQueryRequest` | — | `QueryServiceResponse` |
| `getServiceList` | `ServiceListRequest` | — | `ServiceListResponse` |
| 服务端推送 | `NotifySubscriberRequest`（server→client） | — | 客户端回 `NotifySubscriberResponse` |

**两级分派**：`metadata.type` 只定位到"哪个类"，**动作（register/deregister/batch）在 JSON body 的 `type` 字段**（`NamingRemoteConstants`）。所以 `InstanceRequest` 既承载注册也承载注销，靠 payload.type 区分。

**推送落地**：`start()` 注册 `NamingPushRequestHandler` 为 server-request handler；收到 `NotifySubscriberRequest` 时调 `serviceInfoHolder.processServiceInfo(...)` 更新本地缓存，并回一个空的 `NotifySubscriberResponse` 作 ack。

## 4. ephemeral 活性 = 连接本身（gRPC 模型无 beat）

v2 的 gRPC 路径**没有 `BeatReactor` / 心跳包**：临时实例的存活**就是那条 gRPC 连接**——服务端按 connectionId 登记 client，连接断即实例消失（可选服务端 `ClientDetectionRequest` 探活加速）。§2 里客户端发的 `HealthCheckRequest` 是**反方向**的空闲保活探针（探服务端/连接是否活），不是给实例续命的 beat。beat 只在遗留 HTTP 代理（`NamingHttpClientProxy`）里有。

> 对照 harbor：这正是 [harbor-vs-nacos.md](harbor-vs-nacos.md) §2 / §3.2 的「健康派生自 `ConnectionRecord.lastActiveTime`、`reconcileHealth` 只遍历本节点连接」——**同一模型**，活性即连接。

## 5. 断线重放（redo）：真源在客户端

- `NamingGrpcRedoService`（`client/.../naming/remote/gprc/redo/`）实现 `ConnectionEventListener`。
- **`onDisConnect` 不发任何东西**，只把 `InstanceRedoData` / `SubscriberRedoData` 标 `registered=false`（脏位）；真源是客户端本地的 `registeredInstances` / `subscribes` 映射。
- 重连**不在 `onConnected` 里同步重放**；由 `RedoScheduledTask` 按固定延迟扫 `isNeedRedo()`，逐条重发 register / subscribe / deregister / unsubscribe。
- **「注销优先」在 `RedoData.getRedoType()`**：deregister 先置 `unregistering=true, expectedRegistered=false`，于是死连接下算出的是 `REMOVE` 而非 `REGISTER`——重连不会把已注销的实例复活。

> 对照 harbor：客户端重发的是普通 `InstanceRequest`，harbor 侧 `registerInstance` 幂等即可接住；**harbor 自己不做 client redo**（那是客户端职责，harbor 只保证幂等）。

## 6. 读码时会踩的几个准确点

- **包名 `gprc` 拼写反了，但只在 naming 客户端包**（`client/.../naming/remote/gprc/`）；传输层是 `common/.../remote/client/grpc/`（正常拼写）。别一律按 `gprc` 搜。
- **DTO 不在 `api/.../naming/remote/gprc/`**：命名请求/响应在 `api/.../naming/remote/request/` 与 `.../response/`；连接层 DTO（`ServerCheckRequest` / `ConnectionSetupRequest` / `SetupAckRequest` / `ConnectResetRequest` / `HealthCheckRequest` / `ClientDetectionRequest`）在 `api/.../remote/request/`。
- `metadata.type` 是**类简名**，别按动作名找分派入口。
- 客户端对 SetupAck 是"可选"的（不协商就 sleep 通过）；但 harbor 当前**总是回 `SetupAckRequest`**——更保守，兼容带能力协商的新客户端。

## 7. 与 harbor 的对照

本篇是"客户端视角"，[harbor-vs-nacos.md](harbor-vs-nacos.md) 是"服务端视角"，同一批协议事实的两面：

| 本篇（客户端） | harbor 侧（见字帖） |
|---|---|
| §1 两个 service + Payload/Any | 字帖 §1 概念映射（`Request.request` / `BiRequestStream`） |
| §2 ServerCheck→connectionId、bidi Setup | 字帖 §3.10 connectionId 命名、`HarborServer` 的 `TYPE_*` 分派 |
| §3 两级分派（类简名 + payload.type） | 字帖 §3.13 订阅推送出口、`ServiceStorage` 增删 |
| §4 活性即连接 | 字帖 §2 / §3.2 健康派生自连接活性 |
| §5 redo 注销优先 | 字帖 §3.3 副本续期靠 verify、`reapStaleSyncedClients` |

> 一句话：读完这篇 + 字帖，你就同时握住了"客户端期望什么"和"harbor 怎么满足"这两端——这正是判断兼容性、以及将来若要写原生 `jaws-registry-harbor`（走 wire）时该镜像的请求清单。
