# Harbor ↔ Nacos：一份详细设计对比

> 本文是 **harbor 与 Nacos 注册中心机制的详细设计对比**，逐机制讲清「照做的 / 故意不做的 / 以及代价」。为降低维护成本，对 Nacos 的引用只到 **类 / 概念 / 默认值** 粒度，不再钉 `路径:行号`——行号随版本漂移，逐次重核的负担高于它带来的可查证收益；本文的信用建立在「机制讲清 + 默认值可核对 + 有回归测试钉语义」上。

## 0. 这份文档要解决什么

harbor 的定位有两面：**对内自证 wire 协议**（拿一个真实的注册中心当靶子，而不是写自嗨的 demo），**对外充当读懂 Nacos 注册中心机制的标杆**（每个机制以 Nacos 源码为准绳）。第二面意味着一件事——正确性的考卷在 Nacos 那边，所以「抄对了什么」和「故意没抄什么」都必须讲得出道理。

本文就是那张对照表。主体三段：概念映射（名字为什么这么起）、同源决策（照做的机制，含默认值）、刻意偏离（不照做的以及代价）；另附测试锚定、已知边界、对外咬合的可验证性与维护纪律。第三段是论点所在：**看得懂才敢不抄**。

## 1. 概念映射表

harbor 刻意采用 Nacos 的概念名，使得「读完 harbor 再去读 Nacos」应该是零障碍的。

| harbor | Nacos 对应（类 / 概念） | 为什么是这个名字 |
|---|---|---|
| `ClientSession` | `naming` v2 的 `ConnectionBasedClient` | 一条连接即一个客户端实体，不是「实例列表的容器」 |
| `ClientSession.nativeClient`（`final`） | `ConnectionBasedClient.isNative`（`final`） | 原生/副本身份出生定死，永不在生命周期中翻转 |
| `lastRenewTime` | `ConnectionBasedClient.lastRenewTime`（**同名**，仅 `isNative=false` 有意义） | 曾名 `lastOwnerConfirmedTime`，为消除与 Nacos 的读差改用对方名字；推进它的动作现叫 `onRenew()`——`renew` 是否成立只看「重建这份备份是否合理」，与信号源自 owner 还是本节点无关，故方法名不再背 owner/confirmed 主语，不变式改由调用点与回归测试守 |
| `isReplicaOrphaned(now, tol)` | `Client.isExpire(now)` | 过期判据挂在 Client 上，且只判副本 |
| `ClientSession.connectionId`、两张反向索引的 `Set<String> connectionIds`、`ClientSyncData`/`ClientVerifyInfo` 的 `connectionId` 字段 | `Client.getClientId()` 返回 `connectionId`；载荷里叫 `clientId` | 值就是一个 TCP 连接的 id，"client" 正是让 harbor 早期退回按 IP 匹配的那个词；载荷只在 harbor 节点之间流转，故一并改名，见 §3.10 |
| `recalculateRevision()` | `AbstractClient.recalculateRevision()` | 同名；语义有偏离，见 §3.1 |
| `PushDelayTaskEngine` | `push/v2` 的 `PushDelayTaskExecuteEngine` | 服务级合并的推送延迟引擎 |
| `ConnectionCleanup.cleanup()` | `ConnectionBasedClientManager.clientDisconnected(clientId)` | 关闭动作的唯一事务入口 |
| `HealthCheckScheduler` 的巡检 | `ExpiredClientCleaner.run()` | 巡检只是「复用同一个事务入口」，不另写一套清理 |
| `ConnectionManager`（连接记录 + 活性戳 + client session + 推送出口，四合一） | `core/remote/ConnectionManager`（**只有连接注册表与活性**：`connections`/`register`/`unregister`/`refreshActiveTime`，每 3s 的巡检只 `doEject` 连接） | 名字撞了，**层级与数据域都不同**：见 §2.5 与 §3.9 |
| —（同上，语义那一半） | `ConnectionBasedClientManager.clients`，走 `ClientManager` 接口，且该类 `extends ClientConnectionEventListener` | 「连接」与「客户端」在 Nacos 分属两个模块，靠事件解耦 |
| —（同上，推送那一半） | `Connection implements Requester` + `RpcPushService`（`pushWithCallback`/`pushWithoutAck`） | 写出能力长在连接对象上，下推另有统一入口 |
| `ClientSyncData` | `core/v2/client` 的 `ClientSyncData`（**同名同职责**） | 复制单元是 client 级；Nacos 字段为 `clientId + attributes + namespaces/groupNames/serviceNames + instancePublishInfos + batchInstanceData`，harbor 把三段式服务名并成一个 `serviceKey`；订阅关系两侧都**不出网**（见 §3.8） |
| `ClientVerifyInfo` | `DistroClientVerifyInfo` | 对账只带 `(clientId, revision)` 两个字段 |
| `onSnapshot()` 返回 `List<ClientSyncData>` | `DistroClientDataProcessor.getDatumSnapshot()` → `ClientSyncDatumSnapshot` | 启动加载 = 一次性全量快照，逐 client 构造 |
| `WireHarborNodeTransport` | `DistroClientTransportAgent` | 节点间传输是一个可替换的 agent 薄层 |
| wire 的 `Request.request` 一元 + bidi 推送 | `nacos_grpc_service.proto`：`service Request{rpc request(Payload) returns(Payload)}`、`service BiRequestStream{rpc requestBiStream(stream…) returns(stream…)}` | 注册中心 RPC 面只有这两种形态，见 §5 |
| `conn_cleanup` 写入父通道属性 → 调用上下文 | `Connection.getMetaInfo().getConnectionId()` | 连接 ID 要能在每一次调用中被业务侧拿到 |

## 2. 同源决策：照做的机制与默认值出处

| 机制 | Nacos 默认值 | harbor 值（类） |
|---|---|---|
| 巡检节拍 | `DEFAULT_HEART_BEAT_INTERVAL = 5s`（`Constants`、`SwitchDomain`） | `CHECK_INTERVAL_MS = 5_000`（`HealthCheckScheduler`） |
| 不健康阈值（保留但标记） | `DEFAULT_HEART_BEAT_TIMEOUT = 15s`（`Constants`、`UnhealthyInstanceChecker`） | `INSTANCE_UNHEALTHY_TIMEOUT_MS = 15_000`（`HealthCheckScheduler`） |
| 副本回收阈值（owner 静默） | `DEFAULT_CLIENT_EXPIRED_TIME = 3min`（`ClientConstants`） | `SYNCED_SESSION_TIMEOUT_MS = 180_000`（`HealthCheckScheduler`；A1 已移除 per-instance 180s 过期档，死连接由 90s 看门狗注销） |
| verify 周期 | `DEFAULT_DATA_VERIFY_INTERVAL_MILLISECONDS = 5000`（`DistroConstants`） | `VERIFY_INTERVAL_MS = 5000`（`DistroProtocol`） |
| 启动加载重试 | `DEFAULT_DATA_LOAD_RETRY_DELAY_MILLISECONDS = 30000`（`DistroConstants`） | `LOAD_RETRY_DELAY_MS = 30_000`（`DistroProtocol`） |
| 推送失败重试固定延迟（非指数） | `DEFAULT_PUSH_TASK_RETRY_DELAY = 1000`（`PushConstants`） | `RETRY_DELAY_MS = 1000`（`PushDelayTaskEngine`） |
| 延迟合并的「同键合一任务 + 到点重读」 | `NacosDelayTaskExecuteEngine.addTask` → `newTask.merge(existTask)`；`DistroDelayTask.merge` 保旧动作 | `pending.computeIfAbsent` + 到点 `buildClientSyncData` 重读当前全量幂等推（`DistroProtocol`） |
| verify 不一致 → **owner 定向重推**（不是去 peer 拉） | `DistroClientDataProcessor.syncToTarget(distroKey, ADD, targetServer, 0L)` | `resyncToPeer(peer, clientIds)`（`DistroProtocol`） |
| 健康判定权只属于持有连接的节点，副本只显示不判定 | `ConnectionBasedClientManager.isResponsibleClient(client)` 随两个事件外发 | `reconcileHealth` 只遍历本节点持有的 `ConnectionRecord`（副本无记录 → 天然不判定），翻转经 `healthFlipHandler` 外发（`ServiceStorage`） |
| 广播「当前全量 + 幂等收敛」，无应用层 ack | `NotifySubscriberResponse extends Response`，**无任何字段** | 每次重读当前全量，不缓存旧 payload（`PushDelayTaskEngine` 类注释） |
| 空闲保活 = `HealthCheckRequest`，触发条件是「闲置够久」而非固定定时器 | 默认 `connectionKeepAlive = 5000`（`DefaultGrpcClientConfig`）；`reconnectionSignal.poll(keepAlive)` 超时后比对 `lastActiveTimeStamp` 才发（`RpcClient`） | 5s 巡检 + 90s 连接静默判死（`HealthCheckScheduler`） |
| HTTP/2 PING 只是「无应用层心跳时」的兜底 | `channelKeepAlive = 6*60*1000`（`DefaultGrpcClientConfig`，用于 `GrpcClient`） | 不依赖 PING 做活性判定，PING strike 语义归 core |
| 只有 owner 才对外 advertise 对账数据 | `getVerifyData()` 内 `if (clientManager.isResponsibleClient(client))` 才入列（`DistroClientDataProcessor`） | `runVerifyTask` 只遍历 `allNativeClientSessions()`（`DistroProtocol`） |
| 只有 ephemeral，不做持久实例 | 持久实例走 Raft CP（`consistency` 模块），naming v2 的 `ConnectionBasedClient.isEphemeral()` 恒 true；快照与对账构造时 `!client.isEphemeral()` 直接跳过（`DistroClientDataProcessor`） | 只实现 AP 线，见 §5 |

## 2.5 节点内的两种拓扑角色：分片层与全集群层

判据只有一条：**这个事实除了本节点，别的节点能不能答。** 能答的必然全集群复制，不能答的就是分片。

| 容器 | 角色 | 写入口 | 为什么归这一类 |
|---|---|---|---|
| `ConnectionManager.connections` | 分片 | `register`（仅 `ConnectionSetupRequest` 之后） | 它不是「跟着分片走」，它就是分片归属这件事的定义 |
| `ConnectionRecord.lastActiveTime`（`AtomicLong`） | 分片 | `refreshActiveTime`（门面转发到记录自身） | 活性只有持有 TCP 的节点观测得到；看门狗 `removeStaleConnections` 只摘这一层，判据长在记录上（`isStale`） |
| `ServiceStorage.subscriberIndexes` | 分片 | `addSubscriber` | 订阅只在本地；副本永不进这里（§3.8 收回的就是这一条） |
| `clientSessions` 的 **native** 部分 | **本体** | `register` | 既非备份也非分片：我这个 shard 的权威写侧就在这里发生 |
| `clientSessions` 的 **synced** 部分 | 备份 | `putClientSession` | 别人 shard 的只读副本，供本地答路由查询 |
| `ServiceStorage.publisherIndexes` | 全集群 | 本地注册 + 副本落地两处 | 任何节点都要能回答任意服务的查询 |
| `ServiceStorage.serviceDataIndexes` | 全集群（派生缓存） | 随写路径失效重建 | 由全集群数据算出，自然也是全量域 |

于是基数关系是确定的：`|connections| = 我的 shard 大小`，而 `|clientSessions| = 全集群连接数 ≥ 前者`——两张表共用 `connectionId` 键空间，**域却不同**。

结构上有一条能坐实这两类分界的构造性铁证：`register` 既写连接表也写会话表，而 `putClientSession` **只写会话表**，并且 `refreshActiveTime` 对非本地持有的连接直接跳过。好处是分片归属永不被备份污染（绝不会把别人的连接误当成自己的去推送）；代价是活性层看不见副本——owner 节点一旦死掉，它那批备份没人能按活性收掉，只能由 `reapStaleSyncedClients`（`ServiceStorage`）配 `SYNCED_SESSION_TIMEOUT_MS`（`HealthCheckScheduler`）用「owner 沉默满一个过期窗」单独立一档收尸。这笔账在 §3.9 里也记了一次。

两条边界值得钉住，免得「备份型」被误读成多主可写：**写只发生在 owner**，同步方向永远是 owner 外推，副本只读；**分片键是 TCP 落点而不是 `hash(clientId) % members`**，所以没有 rebalance——客户端重连即自然迁移 shard，其账单由上面那档收尸机制偿还。

Nacos 是同一个形状，但拆在两个模块：一张 `clients` 表同时装两种角色（`ConnectionBasedClientManager`），入口分而合——本节点连接走 `clientConnected(clientId, attributes)`（内部 `clientFactory.newClient`），备份走 `syncClientConnected(...)`（内部 `newSyncedClient`），**两者最后汇入同一个 `clientConnected(Client)` 的 `clients.computeIfAbsent`**，角色只留一个出生即 `final` 的 `isNative` 位（`ConnectionBasedClient`）。而分片那一层在 Nacos 属于传输模块（`core/remote/ConnectionManager`，活性是 `Connection` 对象上的字段）——这正是 §3.9「同名不同层」的实质差异所在。

## 3. 刻意偏离清单

### 3.1 revision：副本侧用自增计数器，harbor 用内容指纹

Nacos 的**基类**是内容哈希：`AbstractClient.recalculateRevision()` → `revision.set(DistroUtils.hash(this))`，`DistroUtils.hash` 逐实例取 `Objects.hash`。但**连接型客户端把它覆盖了**：`ConnectionBasedClient.recalculateRevision()` → `revision.addAndGet(1)`——对连接模型而言 revision 是**每次变更 +1 的计数器**；内容哈希路线只保留给 `IpPortBasedClient`（`DistroUtils` 对其余类型直接返回 0）。

harbor 反其道：把 Nacos 用在另一类客户端上的内容指纹思路搬到连接模型（`ClientSession.recalculateRevision()`，XOR 逐条目哈希）。

理由：verify 的语义是「两节点的数据是否一致」，计数器回答的是「变更发生过几次」。实例增了又删回原样，计数器已前进，peer 会报 mismatch 并触发一次内容完全相同的重推——correctness 不受损，但白耗一轮同步。内容指纹对这种情况判一致。**代价**：32 位哈希理论存在碰撞漏检；XOR 对「同服务两个实例互换」不敏感（同 key 同端口同权重会被抵消），因此条目哈希里带 `ip/port/healthy`，且副本与原生两条路径必须用同一套 XOR 规则（这正是 `ClientSession` 里那条注释强调顺序无关的原因）。

### 3.2 `healthy` 进 revision；活性不落成 per-instance 字段

A1 起 harbor 不再有 `Instance.lastBeat`：ephemeral 健康**派生自持有连接之节点的 `ConnectionRecord.lastActiveTime`**（`ServiceStorage.reconcileHealth`），与 Nacos 2.x 的 `Client.lastRefreshTime` 同构。于是没有"墙钟要不要进哈希"的两难——活性根本不是被复制的数据，副本没有 `ConnectionRecord`、`reconcileHealth` 只遍历本节点持有的连接，天然对它不判定（比原来显式 `isNativeClient` 跳过更干净）。剩下的 `healthy` 仍是**被复制的内容**：owner 无实例变更地翻转判定时，必须可被 verify 检出，否则丢一次推送就让两节点永久分歧（见 `ClientSession` 里 revision 条目哈希的注释）。

### 3.3 合并窗口与 owner 续期：曾偏离 Nacos，现已回归其默认

Distro 同步延迟 Nacos 默认 `1000ms`、推送延迟 `500ms`。harbor 起初两处都取 `200ms` 抢收敛（注册中心 SLA 是「变更多快被看到」、client 级全量载荷小），属刻意偏离；后按口径回归 Nacos 默认——现 `SYNC_DELAY_MS = 1000`、`PushDelayTaskEngine.MERGE_DELAY_MS = 500`，与对端一致。

同一条「回归」还带走了一个 Nacos 本就没有的机制：早期 harbor 另设 owner 每 30s 全量重推自有 client（曾名 `CLIENT_REFRESH`）来给副本续背书时钟。核对后确认副本的 `lastRenewTime` 由 5s verify 在 revision 匹配时推进即已足够，正对应 Nacos `ConnectionBasedClientManager.verifyClient` 命中即 `setLastRenewTime`——owner 沉默即 verify 停摆、副本时钟自然老化、由 `reapStaleSyncedClients`（`ServiceStorage`）配 `SYNCED_SESSION_TIMEOUT_MS = 180s`（`HealthCheckScheduler`）那档兜底，无需额外重推，故删。回归测试见 `SyncedSessionReclamationTest`（零变更副本仅靠 verify 续期即跨窗存活）。

此条保留以记录「偏离→回归」的来龙，免得读者以为 200ms 或那条周期重推仍是现状；编号不动以免打断 §3.9/§3.10 的交叉引用。

### 3.4 不做 payload 缓存重发（删掉 `PushRetryManager`）

曾按「可靠投递」直觉实现过一个重试管理器，读源码后发现 Nacos 的 `NotifySubscriberResponse` 是**空响应**（除 `Response` 基类字段外无任何成员）——根本不存在应用层投递信号可供重发决策。缓存旧 payload 重发在「到点重读全量」模型下是**倒退**：乱序的一帧会把订阅者刷回过期视图。故删除，改为「连接在则 skip、异常则固定延迟重排一次新的全量推」。

### 3.5 不做 `protectThreshold`

值得单列，因为核对源码后结论反而**变轻**了：Nacos 的 `ServiceMetadata.protectThreshold` 默认 `0.0F`，而在 v2 里它的唯一使用点是 catalog 侧把「是否处于保护态」渲染成一个字符串——`isProtectThreshold = healthyCount * 1.0 / ipCount <= threshold`（`CatalogServiceV2Impl`）。实例选择路径（`ClientService` / `ServiceStorage`）里**没有任何**「健康实例过少就返回全量（含不健康）」的保护性回退——那是 1.x 的路由器行为，v2 已退化成展示字段。

所以 harbor 不做它，与 Nacos v2 的实际行为一致；真正与 1.x 直觉冲突的是 harbor 刚定下的「如实标 unhealthy」：一个要兜底返回、一个要不健康即不可用。harbor 选后者，语义更硬，代价是极端误判场景没有兜底。**若将来引入客户端侧的降级路由（或 Nacos 把保护态做回选择路径），这条要重新评估。**

### 3.6 不可靠的机制删掉，不加补救参数

三例：`connectionId` 的 clientIp 兜底、bi-stream 里的 `UUID` 兜底、`connectionId` 的事后可变（改构造注入 + `final`）。共同点是该值「取不到时怎么办」无论怎么答都可能把一条连接的生命周期记到错误实体上，静默错位比失败更难查。所以直接要求它必然存在（`HarborServer` 相关分支），代价是任何未预料的时序会显式抛错而非降级。

### 3.7 连接属性传播做成可配置键集，wire 层不解释语义

Nacos 不需要这个：它的调用上下文本身就在 `Connection` 对象上。harbor 的 wire 层要在 HTTP/2 子通道上拿到父（TCP）通道属性，最初把 `CONNECTION_ID` 硬编码进 dispatcher——那等于让通用传输层持有业务概念。现改为 `WireServer.addConnectionAttributeKey(String)` 注册 + `mergeConnectionAttributes()` 只搬运不解释。

### 3.8 订阅关系不出网（曾复制，已按 Nacos 收回）

Nacos 的复制载荷由 `AbstractClient.generateSyncData()` 生成，**只遍历 `publishers`**——订阅是 `AbstractClient` 上的本地 map，从不过网；触发侧同理：harbor 只在 register/deregister/batch 与健康翻转时请求同步（`HarborServer`），订阅本身不触发任何同步。附带一处同名对照：Nacos 把 revision 作为 client attribute 搭在同步载荷里（`addClientAttribute(REVISION, getRevision())`），harbor 则是 `ClientSyncData` 的显式字段。

harbor 早期版本在载荷里多带了一个 `subscriberKeys`，并计入 `hasContent`。核对本表时把它删掉了，因为两条害处都是实打实的：

1. **污染本节点的推送目标索引**。副本落地时会把自己的 clientId 写进 `subscriberIndexes`（`ServiceStorage.getSubscriberConnections` 的语义因此变成谎话——「本节点当前订阅者」里混进了写不到的连接，`PushDelayTaskEngine` 每一轮推送都要空查一次并记一条 "connection gone"）。
2. **造出没有删除路径的副本壳**。纯订阅副本没有实例、也没有 `ConnectionRecord`，`reconcileHealth` 只遍历本节点持有的连接故永远看不见它；而连接关闭时的 DELETE 判据只看 `serviceKeys`（`ConnectionCleanup`），于是这种壳只能等 180s 孤儿回收兜底。

**实测证据**（3 节点集群 19848/19849/19850 + provider/consumer 各起一次）：provider 连到 19848 注册 2 个服务，另两节各自日志出现一条 `applied client sync: <connId> (publishers=2)`（跨节点发现不受影响）；consumer 只订阅，其连接在另两节点上**零**条同步记录，全集群 `publishers=0` 出现 **0** 次、`distro delete client` **0** 次——即「无内容的壳」这一形态在集群里已不存在。

`ConnectionCleanup` 的 DELETE 判据因此不需要扩展看订阅：没有订阅被复制，就没有需要撤销的订阅副本。回归由 `SubscriptionStaysLocalTest` 钉住（载荷不含订阅 / 就算被请求同步也只会是 DELETE / 副本永不进推送索引），mutation check 双注入验证过非假绿。

### 3.9 `ConnectionManager`：Nacos 的三层，harbor 的一层（**决定不拆**）

Nacos 把这件事切成三层，中间用事件解耦：

- **连接与活性**：`core/remote/ConnectionManager` 只持 `connectionId → Connection` 与活性刷新（`refreshActiveTime`），外加连接治理（按 label 计数、`loadCount`/`redirect` 迁移、ejector 主动踢）；每 3s 的巡检只 `runtimeConnectionEjector.doEject()` 处理**传输层连接**。
- **客户端语义**：`ConnectionBasedClientManager.clients: clientId → ConnectionBasedClient`，类本身 `extends ClientConnectionEventListener`，靠 `ClientReleaseEvent`/`ClientDisconnectEvent` 与传输层握手。
- **下推出口**：写出能力长在连接对象上（`core/remote/Connection implements Requester`），服务端下推统一走 `core/remote/RpcPushService.pushWithCallback/pushWithoutAck`。

harbor 把四样东西装进一个类：连接记录（`ConnectionManager.connections`）、活性时钟（长在记录自己身上：`ConnectionRecord.lastActiveTime` + `refreshActiveTime`，由门面同名方法转发）、client session 表（`putClientSession` 等于让 Distro 把手伸进传输层注册表）、推送出口（`pushToConnection` 直接 `pushSubject.onNext`）。

**判断是不拆**。harbor 只有约 50 个 Java 文件，模块边界已经能由类名表达，再拆一层 `ClientSessionManager` 换来的是类图相似而非正确性，代价是要动 `DistroProtocol`/`ServiceStorage`/`HarborServer` 三处引用面。

但这条偏离的代价不是零，而且要分清哪一半已经还了。原来活性戳存放在与连接表并列的第二张 map 里，`putClientSession` 从不写它——两张表必须同步是条隐形契约，漏一处就泄漏。现已把时钟并进 `ConnectionRecord`，只剩一张表，**这条漂移由构造消灭了**。没消掉的是另一半：副本压根没有 `ConnectionRecord`，所以看门狗（只看活性）结构性地看不见副本——「owner 死后副本无人收」那个漏正是这个形状长出来的，今天靠 `reapStaleSyncedClients` + `SYNCED_SESSION_TIMEOUT_MS` 单独立一档兜住（`HealthCheckScheduler`）。留此记录，是为了下次有人想说「顺手再加一张表」时能看到：合层的账是按档叠加还的。

**同构之处也值得记一笔**：`removeStaleConnections` 只摘活性层、把 session 留给 `ConnectionCleanup` 单入口收尾，与 Nacos「`doEject` 只处理连接、语义清理走 `clientDisconnected`」是同一个分层判断。

**重评触发条件**：一旦要做连接治理（按 label 限流、负载迁移、主动踢连重平衡），或者要让多协议共用同一张连接注册表，把传输层拆出来才有真实收益——那时再拆。

### 3.10 全仓改叫 connectionId，包括 Distro 载荷

Nacos 在 `ClientSyncData` 与 `DistroClientVerifyInfo` 里把主键字段叫 `clientId`（`ConnectionBasedClient.getClientId()` 返回的其实也是 `connectionId`），harbor 连同自己的载荷模型一起改叫 `connectionId`：`ClientSession.connectionId`、两张反向索引的 `Set<String> connectionIds`、`ClientSyncData.connectionId`、`ClientVerifyInfo.connectionId`、`DistroVerifyResponse.mismatchedConnectionIds`。

改名没有兼容代价，因为这两个类是**节点之间**的 Distro 载荷（JSON 装进 `Payload.body`，类型名 `DistroSyncRequest`/`DistroVerifyRequest` 都是 harbor 自己的），nacos-client 既不发也不读；harbor 集群两端同步演进即可。与 nacos-client 互通的那一面是 `NotifySubscriberRequest`/`InstanceRequest` 那批，字段名一律照旧。

值得这个名字的理由是硬的：`client` 一词在读者心里默认指进程或主机，harbor 早期就因此留过 `connectionIdByClientIp` 之类的按 IP 兜底，而同一台机器起多个进程时那会互相覆盖（§1 的第一条不变式）。值是一个 TCP 连接的 id，就叫它 connectionId。

同类的名字取舍还有两处：副本背书时钟采用 Nacos 的 `lastRenewTime`（消除读差）；推进它的动作也从曾起的 `markOwnerConfirmed()` 改回中性的 `onRenew()` —— 早先带 owner/confirmed 三个词是为防「副本自我续期」的误读，但那道不变式其实该由调用点与回归测试兜，压在方法名上反而抬高阅读成本；`renew` 只要「重建这份备份合理」即成立，无论源自 owner 还是本节点。字段与访问器的注释承载语义，方法名从简；命名史与 Nacos 对照一律收在这里。

### 3.11 verify 的补偿重推：Nacos 走事件解耦，harbor 就地 inline

Nacos 的「verify 不一致 → owner 定向把该 client 重推给报了缺失的对端」这条自愈**不在 verify 执行体里**，而是拆成异步事件：发送侧 `DistroVerifyExecuteTask` 只把 verify 数据发出去、回调仅记 metric，不 resync；拿到响应后 `DistroClientTransportAgent` 的 verify 回调对失败的 client `publishEvent(new ClientEvent.ClientVerifyFailedEvent(clientId, targetServer))`，监听者 `DistroClientDataProcessor.syncToVerifyFailedServer` 再 `distroProtocol.syncToTarget(distroKey, ADD, targetServer, 0L)`（注释「Verify failed data should be sync directly」）。

harbor 把这条链**压平**：`runVerifyTask` 在 `syncVerify` 直接拿回 mismatch 列表后就地 inline 调 `resyncToPeer(peer, mismatched)`（方法见 §1「verify 不一致 → owner 定向重推」行），方向、语义与守卫（只推 owner/native、`hasContent`）都和 Nacos 一致，只是不引事件总线、同步推。这取舍与 §3.9「harbor 把 Nacos 三层压成一层」同源——项目刻意不上事件解耦，能内联的链路就内联。

**别误读成多余**：这是该分歧唯一的自愈路径（除它之外，副本缺/落后的 client 没有别的补偿，启动 load 不会再跑），删 `resyncToPeer` 即正确性回退，不是精简。

### 3.12 出向同步：harbor 单键广播 vs Nacos 的 (client, target) 队列

Nacos 的延迟合并任务按 **(clientId, resourceType, targetServer)** 三元组排队：`DistroProtocol.sync` 对每个 peer 各调一次 `syncToTarget`，后者把待推包成 `distroKeyWithTarget = new DistroKey(resourceKey, resourceType, targetServer)` 再 `addTask`，即「每连接 × 每对端」一条待办、各走各的 worker。harbor 把合并键压回**单 `connectionId`**（`requestSyncChange` 的 `pendingSync.computeIfAbsent`），到点 `syncChange` 一次**扇出给所有 peer**：build 一次、把同一份 `content` 发 N 份。

这把不对称两边各有账：

- **harbor 赢在合并率与 CPU**：发往各 peer 的是同一份 client 全量状态，Nacos 的 per-target 键对「内容对所有 peer 一致」的模型是**重复记账**——N 条待办、N 次取数/序列化。一次 build + 广播字节，合并不输反省。
- **harbor 输在 peer 隔离与健康短路**：`syncChange` / `runVerifyTask` 都是**在一个 scheduler 线程里对 peer 串行阻塞**地 `syncData`（request/response 到超时），一个死/慢 peer 会把它后面所有 peer 一起堵住（队头阻塞）；且 harbor 成员纯静态配置、**没有 peer 健康态**，死节点永远留在发送集里每次白等。Nacos 每 (client,target) 独立派发，且发前一律 `checkTargetServerStatusUnhealthy` 短路跳过不健康 target（`DistroClientTransportAgent`）。

**关键判断**：per-target 的这两点好处，本质来自「按-peer 异步派发 + peer 健康门」，**不来自那个队列键本身**——键只是把这两件事顺带编码进了调度。所以真要补齐，harbor 的正解是「把扇出丢到 executor 上按 peer 并行」+「给 `ClusterManager` 加连续失败摘除」，而**不是**照搬 (client, target) 队列（那只会平白 N× 记账，违背 §3.9 一层化与「不为用不上的对称性补基础设施」）。

**当前决定：先不动。** harbor 数据量小、集群 3–5 台、超时短，HOL 实测不痛；此条按「已知、刻意接受的偏离」入账。一旦推向多 peer 大集群，再按上面两条正解处置，届时把本行升级为「已实现」。

### 3.13 订阅推送出口：只在可路由集合真变化时推，空服务退役静默

Nacos 的推送是「变更驱动」的：`PushDelayTaskExecuteEngine` 只在服务实例/元数据真变化时按服务键排一个合并延迟任务，到点 `PushExecuteTask` 重读**当前**实例与**当前**订阅者再下发；`NotifySubscriberResponse` 是空响应，不存在逐条投递确认。harbor 与之同构（见 §1 的 `PushDelayTaskEngine`、§3.4）。

由此推出一条常被写错的不变式：**「要不要推」的判据是「存活订阅者看到的路由集合变了」，而不是「我碰了一下这个服务」**。落到 `ServiceStorage` 的移除路径上，就是两件正交的事，曾长期挤在一个 `checkAndCleanEmptyService(key, dataChanged)` 里、用一个布尔只控制其中一半（读起来别扭、且 `dataChanged=true` 时几乎整段都在空跑），现已拆成两个具名方法：

- **`announceChange`**——服务仍存活时，把新的（可能为空的）实例列表推给订阅者。**只有真改了可路由集合的调用方才调它**：register / deregister / 健康翻转 / 断连 / 副本失联。而 5s 巡检与「只是有人退订」不调它——否则每轮把每个空闲服务全量重推一遍（`NotifyOnChangeOnlyTest` 钉住这条，对齐 Nacos「push on change」）。
- **`retireIfEmpty`**——一个服务既无 publisher 又无 subscriber 时，把它从所有索引与缓存里删掉。**这一步静默、不推送**：因为「让存活订阅者收到那份空列表」已经由 `announceChange` 在最后一个实例被移除的那一刻发过了；等到两个索引都空，本节点已无可通知的对象，即便调一次 `onServiceChange` 也是发向零个连接（何况推送是延迟合并、到点重读活订阅集的，`PushDelayTaskEngine` 那一帧本就投递给零个收件人）。

代价与边界：退役不推，意味着「服务被回收」这件事对客户端不可**单独**观测——但客户端其实无需观测它，它早在收到那份空列表时就知道该服务没有可用实例了；真掉线漏推的订阅者，重连时按 `SubscribeService` 拉全量自愈。

这套语义的回归锁在 `NotifyOnChangeOnlyTest` 与 `SyncedSessionReclamationTest`，二者对 listener 计的是 `onServiceChange` 的**调用次数**、不是真实投递——所以断言「有存活订阅者的移除恰好一次、无存活订阅者的移除（含退役）零次」才是这套出口的真锁；改这些分支务必跑全模块（别只跑同名测试）。

### 3.14 空服务清理：即时 both-empty 退役 vs Nacos 惰性 publisher-only + 宽限

判"一个服务空了该回收"，harbor 和 Nacos `EmptyServiceAutoCleanerV2` 走两条路：**候选集等价，但判据和时机都不同**。

- **候选集（等价）**：Nacos 遍历 `ServiceManager` 的 per-namespace 服务单例全集；harbor 没有 ServiceManager 这张表，用 `publisherIndexes ∪ subscriberIndexes` 的 keyset 并集枚举——一个服务只要还有 publisher 或 subscriber 就还在索引里，所以两者都是"枚举当前存在的服务"，语义对齐。
- **空判定谓词（刻意不同）**：Nacos 只看 `getAllClientsRegisteredService(service)` 为空——**只数 publisher**，不管还有没有订阅者；harbor 的 `retireIfEmpty` 要 **publisher 与 subscriber 都空**才退役。后果：一个"0 实例但仍有人订阅"的服务，Nacos 到期照清，harbor 会留着——这与 §3.8「订阅不出网、`subscriberIndexes` 是分片本地」自洽：留着才能让存活订阅者继续持有那份空列表，退役时机交给"最后一个订阅者退订"（见 §3.13）。
- **宽限与触发时机（刻意不同）**：Nacos 纯惰性——`isTimeExpired` 要求空状态持续超过 `emptyServiceExpiredTime`（默认 60s），且由后台 cleaner 每 `emptyServiceCleanInterval`（默认 60s）扫一次，**deregister 当下不删**，空服务带着空状态驻留至少一个过期窗。harbor 即时——`deregisterInstance / removeSyncedClient / removeSubscriber` 当场调 `retireIfEmpty`，both-empty 立刻清；`cleanEmptyServices()`（HealthCheckScheduler 每 5s 巡检后）只是**兜底扫**（补漏 + 竞态）。harbor 没有 per-service 的"空满 N 秒才清"宽限。

一句话：harbor 把"回收空服务"从 Nacos 的**惰性 + 宽限**改成**即时 + 兜底扫**，并把判据从"无 publisher"收紧到"无 publisher 且无 subscriber"——两处都服务同一目标：让存活订阅者的视图收敛由推送驱动、即时且幂等，而不是靠一个后台定时器去追平。

## 4. 测试即语义注解

`jaws-harbor` 的主干测试类各自钉住一条 Nacos 语义，类名就是命题：

| 测试 | 钉住的语义 |
|---|---|
| `EphemeralHealthTierTest` | 连接静默 15s 标不健康且保留、连接恢复活动则 reconcile 回健康（活性驱动，双向） |
| `SyncedHealthAuthorityTest` | 健康只由持有连接的节点判定，副本只显示（§2/§3.2） |
| `SyncedSessionReclamationTest` | 孤儿副本回收判据是 owner 沉默、不被本地改动赦免；零变更副本仅靠 5s verify 匹配续期即跨窗存活（§3.3 删 owner 重推后唯一周期信号）；副本侧 CHANGE 只在 revision 变时通知本地订阅者、幂等 resync 不推；副本被收时若仍有存活订阅者则播报（§3.13） |
| `SubscriptionStaysLocalTest` | 订阅不出网：载荷不含订阅、副本永不进推送索引（§3.8） |
| `WatchdogClosureTest` | 快照必须在摘除 session 之前取，否则漏发 DELETE |
| `NotifyOnChangeOnlyTest` | 只为真实数据变化播报：巡检与退订不触发全量重推；空服务退役静默（发向零订阅者的空推已删），存活者的空列表由 `announceChange` 推（§3.13） |
| `PushDelayTaskEngineTest` | 服务级合并 + 到点重读当前全量 + 连接已断则 skip |
| `HarborDistroProtocolTest` / `HarborDistroClusterTest` | verify 不一致 → owner 定向重推的修复方向 |
| `ClusterFilterSemanticsTest` | `cluster` 白名单与 `healthyOnly` 是**读投影**而非装饰：query 与订阅响应丢 disabled、推送保留 disabled（让订阅者知道自己那台退了服务）；`clusterName` 要能穿过注册与投影往返 |
| `ServiceListPagingSemanticsTest` | `pageNo/pageSize` 真分页（1-based、越界回空、尾页截断），而 `count` 是整个匹配集大小——页不满不等于还有下一页 |
| `CapabilityBoundaryTest` | 范围外的请求要读成「能力边界」而不是「handler 丢了」：`PersistentInstanceRequest`/fuzzy watch 走显式不支持，未知 token 仍报 unknown；持久实例在 client 侧与 server 侧都先拒后写，拒了就不留任何状态 |
| `StaleStreamClosureTest` | 关闭事务按**流身份**守卫：连接标识是 TCP 连接，同通道重连会复用同一个 id，迟到的旧流 END 不许拆掉刚建立的新会话（否则客户端重放回来的实例被莫名抹掉） |
| `client/NodeSelectionTest` | 起点在节点列表里**随机**（对齐 `NamingServerListManager.start()` 的 `currentIndex.set(random)`）：上百个 provider 进程不该全压列表第一项、等它挂了才散开；候选顺序从游标走而非从顶部重数 |
| `ClientFailoverSemanticsTest` | 恢复先探活当前节点（`HealthCheckRequest`，同 `RpcClient.reconnect` 请求失败前的 `healthCheck()` 短路）：节点还活着就只在那条通道上重开通知流、**不换 TCP 连接**，身份因此不变；探不到才按游标轮转换节点。挂掉的节点被接替后**重放自己的状态**：两个节点故意不 join 集群，所以第二台之所以有数据只可能是客户端自己搬过去的；只有一个地址且不可达时当场报错并点出那个地址（此处与 Nacos 有意不同：它 `while(!switchSuccess)` 无上限退避重试，harbor 选择响亮失败，长驻恢复交给 keepalive 下一轮） |
| `ConnectResetSemanticsTest` | 服务端 `ConnectResetRequest` 双向契约：客户端先回 `ConnectResetResponse` 再重连重放；超时不回则由服务端自己跑关闭事务（对齐 Nacos `loadSingle` 的 3s 等 ack）；带 redirect 时整份状态搬到另一节点 |
| `client/HarborClientTest` / `client/RedoDataTest` | 原生 client 的跨实现互证（自家 client 写、真 nacos-client 读，反之亦然）与 `RedoData` 三 bool 决策表 |

## 5. 已知边界（不是偏离，是尚未做）

- **AP 线之外的东西一概不做，但一律响亮拒绝**：持久实例（要 CP/Raft 存储＋服务端主动探活＋活过连接的生命周期）、3.0 才有的模糊订阅（要 pattern→services 索引＋另一套订阅者身份与对账）、配置中心与鉴权/console/k8s-sync 都不在 harbor 范围内（产品面，研读优先级低于机制层）。成本参照：Nacos 的 `consistency/persistent` ＋ `healthcheck` 两处约 3.6k 行，再加 `PersistentClientOperationServiceImpl` 559 行——这不是补一个 DTO，是往「AP ＋ 连接即活性」里塞第二套一致性档位与第二个健康权威。
  边界必须可听见：`HarborProtocol.UNSUPPORTED_REQUEST_TYPES` 里的已知类型回「not supported ＋ 一句范围说明」，未知 token 才回 unknown，日志与客户端异常据此能分清「不支持」和「我们漏了」。**自家 client 更严**：`ephemeral=false` 在 `HarborClient` 直接抛 `IllegalArgumentException`，服务端也拒带 `ephemeral=false` 的 `InstanceRequest`——Nacos 服务端只按请求类型分流、并不查这个字段，但 nacos-client 永远不会这么发，所以严格化不破兼容；否则它就是静默把持久实例降级成连接所有物，正是持久实例定义里不允许的那件事。
- **节点间全是一元**：与 Nacos 同形——Nacos 注册中心自己的 gRPC 服务面只有 unary `request` + 一条 bidi 连接流（`nacos_grpc_service.proto`），仓库里带 streaming 的只有 vendored 的 Istio/MCP proto（`mcp.proto`），在 `istio/src/main/java` 里没有任何 `rpc` 实现引用；连 jraft-core 的 `installSnapshot` 都是分块多请求而非 gRPC 流。规模化风险点在启动快照——万级 client 时单包 JSON + 5s 一元超时会痛，届时 client-stream 是自然形态，属「用对原语超越 Nacos」而非补角。
- **批注册的形状，与「批注销」的真相**：`BatchInstanceRedoData extends InstanceRedoData`（与 Nacos 同形），重放按表项自己的形状决定发单个还是发批。Nacos 的批量注销 `batchDeregisterService` 并不是新请求，而是**取本地全量减掉要删的、再把余集整体 batchRegister 回去**——因为它的批语义是「替换该 client 在该服务上的实例集」。这解释了为什么 `NamingRemoteConstants` 只有 `BATCH_REGISTER_INSTANCE` 而没有批注销常量，也解释了为什么服务端 `BatchInstanceRequestHandler` 的 switch 只认批注册、其余 `throw Unsupported request type`：harbor 服务端此处与它**完全一致**，不需要改。harbor 的 `deregisterInstance` 本就是实例级的，所以客户端逐条反注册即可，但**redo 表项必须随之收缩到余集**（否则重放会忠实 resurrect 被删掉的实例）；同一性按 ip+port+cluster 判，不像 Nacos 那样用 `toString()` 等值——权重或健康一变，余集就算不上了。
- **已决并落地**：纯订阅连接的复制/回收不对称选了 B——订阅关系整体退出复制载荷，向 Nacos 靠齐（详见 §3.8，含 3 节点集群实测证据）。
- **已订正**：`ConnectionCleanup` 的 Javadoc 曾把对标对象写作 Nacos 的 `ConnectionManager` + `ClientConnectionUnregisterEvent`（该符号在 Nacos 不存在）。本文改为真实的 `ConnectionBasedClientManager.clientDisconnected(String)`（`clients.remove` → `release()` → 以 `isResponsible` 发 `ClientReleaseEvent`/`ClientDisconnectEvent`），并点明 Nacos 自己的看门狗 `ExpiredClientCleaner` 也复用这同一个入口——正是本类要编码的性质。

- **空列表保护归消费层，注册中心两层都不做（已决）**：健康是按**连接**判的，静默过 `INSTANCE_UNHEALTHY_TIMEOUT_MS=15s` 就把该 client 在所有服务上的实例**一起**标 unhealthy，活动回来再一起翻回——余量只有 3 拍（客户端 keepalive 5s ÷ 15s）。所以一次超过 15s 的客户端停顿（长 GC、合盖、NAT 静默丢包而 TCP 未断）在消费侧表现为"服务突然空了"，而 90s 看门狗之后实例是被**删除**而非降级，那时任何阈值都无从回退。Nacos 的两个开关都不解决这个问题：服务端 `protectThreshold` 默认 0.0（等于没开），客户端 `namingPushEmptyProtection` 默认 false 且判据是 `ServiceInfo.validate()`——"列表里还有没有一个 healthy 且 weight>0 的实例"，一旦开启连**正常清空**也会被当错误推送忽略，客户端死抱旧地址；nacos-client 也**没有**"忽略后延迟反查"这回事，恢复只靠下一次有效推送、显式 `subscribe=false` 查询，或重连时 redo 重投订阅带回新快照。harbor 的兜底因此只放在消费层，且已经在那儿：`RegistryDirectory.notify` 收到空列表**保留既有引用**并 warn，`AbstractRegistry` 另有本地文件缓存。既然职责划到消费层，服务端就不实现 `protectThreshold`，**原生 `HarborClient` 也不加 nacos 那套 `namingPushEmptyProtection` 的等价物（更不做「忽略＋延迟反查」）**——否则同一个判断会在服务端、客户端、消费层各存一份，而三者的"空"含义还不一样。代价要说清：裸用 nacos-client / HarborClient 而不经 jaws registry 层的调用方没有这层保护，它得自己决定拿到空列表时是否切流量。可观测性补上了：翻转与恢复各一行日志（unhealthy 为 WARN，带静默毫秒数与受影响的实例/服务数）。
- **元数据/服务编辑面不做**：`NacosNamingMaintainService` 走 HTTP（`NamingHttpClientProxy`），服务端 `NamingMetadataOperateService` 把 `ServiceMetadata` 变更提交给 **CP 协议（JRaft，group=`SERVICE_METADATA`）**；`ServiceMetadata` 还携带 `selector` 与 `clusters: Map<String, ClusterMetadata>`（内含服务端主动探活 tcp/http/mysql）。这三样各自撞上 harbor 的立身前提：没有 CP 层、健康权威只有"连接即活性"、gRPC 面上不存在元数据写入请求类型。故 harbor 不做元数据写面，README 也不宣称支持。

## 6. 对外咬合的可验证性：拿同栈的 spacecloud 当第三方反验

harbor 的定位决定了它不能只靠自证。**自测全绿 ≠ 协议互通**：`jaws-to-jaws` 两端同源，双命名体系的问题会被同一套反射口径互相掩盖，跑再多遍也证明不了「真 nacos-client 能把它当 Nacos 用」。所以咬合的正确验法永远是**从对面打过来**——用一个你不控制、生态现成的客户端反向验证。当前这条腿是 `run-sample.sh interop`（grpc-java ↔ jaws-wire 双向）加 §3.8 的 3 节点集群实测。

`spacecloud` 是这条铁律最顺手的延伸：它是同栈的姊妹仓，**核心技术栈与 jaws 同向（Dubbo + gRPC + Nacos），Spring Cloud 侧注册中心用的就是 Nacos**。三根齿与 jaws 一一对应——wire ↔ 它的 gRPC、harbor/nacos 注册 ↔ 它的 Nacos、jaws 协议 ↔ 它的 Dubbo。于是它天然是那面「不控制、现成、异构」的镜子：

- **harbor 的端到端反验**：让 `spacecloud` 里那套**真 Dubbo 的 Nacos 注册实现**（官方 Dubbo 生态的 nacos 注册，非 harbor 自带的测试替身）去连 harbor。它若能把 harbor 当 Nacos 完成注册 / 发现 / 订阅，就是比单测更硬的证据——真 Dubbo 生态的 nacos 客户端行为最接近生产。反过来，jaws 里任何 registry 语义的纠结（退役要不要推、副本靠什么续期、订阅出不出网）都能拿 `spacecloud` 的真 Dubbo + Nacos 表现当**参考答案**来定口径。
- **wire 的端到端反验**：`spacecloud` 的 gRPC 那根齿可当 jaws-wire 的互通对端，比只跟 `grpcurl` 验更真——真 grpc-java stub 的双向调用能顺带压出 `grpc-status` 富错误、deadline、压缩这些 wire 级约定。

已经落成的一条是**两条注册腿的互相可见**：`./run-sample.sh harborx`（jaws-registry-nacos ＋ 真 nacos-client 连 HarborServer）与 `harbor`（原生 client）跑在同一条 `harbor-standalone` 上时，原生腿的 consumer 会调用到 nacos 腿 provider 导出的端口（实测 `server => 192.168.10.120:20001`，服务端同时留着 `version=Nacos-Java-Client:v3.2.3` 与 `version=jaws-harbor-client/1.0` 两类会话）。这条不是"两家自测"，而是两个**不同实现**在同一份 wire 契约上互操作——也正是 `HarborPathUtils` 要求两条腿 URL↔实例映射逐字一致的原因。它仍不替代下面 spacecloud 那条：那是拿"你不控制的第三方实现"来验。

两条腿等价性的实测记录（一台 `harbor-standalone`，先后跑 `harbor` 与 `harborx`，服务端日志按事件签名归一化后分段比对；裸 diff 会被时间戳、connectionId、ip:port 淹没）：

| 事件 | 原生腿 | nacos 腿 |
|---|---|---|
| `connection registered` | 2 | 2 |
| `instance registered` / `instance deregistered`（两服务） | 2 / 2 | 2 / 2 |
| `empty service cleaned`（退役） | 2 | 2 |
| `bi-stream completed` / `connection removed` | 2 | 2 |
| `ERROR` / `WARN` | 0 / 0 | 0 / 0 |

**命名链路无差异**，两边业务调用同样跑通。三处差异都不构成缺功能：

- `clientVersion` 标识不同（`Nacos-Java-Client:vX.Y.Z` 与 `jaws-harbor-client/1.0`），本就该不同。
- **传输层 keepalive**：nacos-client 会发 HTTP/2 PING，原生 client 只发应用层 `HealthCheckRequest`。这不是缺陷——`WireKeepaliveHandler` 按 gRFC A8 判「两次 PING 之间有无数据帧」，而 nacos 每 5s 那次 `HealthCheckRequest` 本身就是数据帧、会重置判定窗口；实测 5 条 PING 全部 `strikes=0/2`、零次记 strike。真要补 PING 的时机是"接入方中间设备的空闲回收只认传输层探测"，与 harbor 的活性判定无关（服务端活性时钟只被消息刷新，PING 不刷新它）。
- GOAWAY 计数 1 : 2，两端关闭路径细节（先 GOAWAY 再断 vs 直接断链）不同，无功能含义。

两条**已被排除的解释**，留此免得被重复试探：① 为消掉配置中心噪声而把 `NacosDynamicConfiguration.init()` 短路，结果开关两种状态下服务端都**没有任何 config 流量**（`ConfigService` 建了但无人注册 listener，就不发长轮询）——这个开关对本比对是多余的，两腿日志本来就干净可比；② 第一段里那条 `no peers to load from — running as single node` 不是腿间差异，是 server 启动后 +1s 的 Distro 一次性加载恰好落在分段线上。

**这一节其余部分记方向，不记已完成**：上面两条反验目前是计划中的联调靶子，尚未落成 `run-sample.sh` 里的固定用例，真正接起来之前别把它们当现状读。它也顺手给「注册中心只对齐 Nacos、不加 ZK / Consul」补了体系自洽这条硬理由——同向锚点在你自己的多仓体系里已经是 Nacos，再钉一根对不上的齿是拆自己的台。

## 7. 维护纪律

1. 新增或改动 harbor 机制时，必须在 §1/§2/§3 中落一行，指明对应的 **Nacos 类 / 概念**（带模块路径）与取值或取舍理由——不接受「Nacos 也是这么做的」这种无对象的断言。
2. 引用只到 **类 / 概念 / 默认值** 粒度，**不钉 `路径:行号`**。行号随版本漂移，逐次重核的负担高于其可查证收益；本文的信用建立在「机制讲清 + 默认值可核对 + 有回归测试钉语义」上，而非「引用可点开到行」。
3. 常量对齐就对齐 Nacos 默认值；要对齐得不一样，必须进 §3 写清代价与回滚动作。
4. 命名优先采用 Nacos 的概念名。改名若降低了「读完 harbor 能读 Nacos」的顺滑度，就是负收益。
5. 改动订阅推送这类「有回归测试锁语义」的机制时，务必同步更新 §4 的测试锚定，并跑全 `jaws-harbor` 模块（不止同名测试）。
