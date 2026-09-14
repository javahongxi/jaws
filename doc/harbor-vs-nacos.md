# harbor ↔ Nacos：一份对照字帖

> 本文基于两侧源码逐行核对撰写。核对基线：Nacos `1a66d61b1`（本地 `~/github/nacos`）、jaws `9b79105e`（`jaws-harbor` 主源码 50 个 Java 文件 / 6727 行，单测 57 个）。所有 Nacos 出处的形式为 `路径:行号`，所有常量值均读自源码而非文档。核对日期 2026-09-14。

## 0. 这份文档要解决什么

harbor 的定位有两面：**对内自证 wire 协议**（拿一个真实的注册中心当靶子，而不是写自嗨的 demo），**对外充当读懂 Nacos 注册中心机制的标杆**（每个机制以 Nacos 源码为准绳）。第二面意味着一件事——正确性的考卷在 Nacos 那边，所以「抄对了什么」和「故意没抄什么」都必须能被逐条查证。

本文就是那张可查证的表。主体三段：概念映射（名字为什么这么起）、同源决策（照做的机制，含数值出处）、刻意偏离（不照做的以及代价）；另附测试锚定、已知边界与维护纪律。第三段是论点所在：**看得懂才敢不抄**。

## 1. 概念映射表

harbor 刻意采用 Nacos 的概念名，使得「读完 harbor 再去读 Nacos」应该是零障碍的。

| harbor | Nacos | 出处 | 为什么是这个名字 |
|---|---|---|---|
| `ClientSession` | `ConnectionBasedClient` | `naming/.../core/v2/client/impl/ConnectionBasedClient.java:29` | 一条连接即一个客户端实体，不是「实例列表的容器」 |
| `ClientSession.nativeClient`（`final`） | `isNative`（`final`） | 同上 `:37`、`:61` | 原生/副本身份出生定死，永不在生命周期中翻转 |
| `lastRenewTime` | `lastRenewTime`（**同名**） | 同上 `:42`（注释：仅 `isNative=false` 有意义） | 曾名 `lastOwnerConfirmedTime`，为消除与 Nacos 的读差而改用对方名字；推进它的动作仍叫 `markOwnerConfirmed()`，因为 renew 不含主语，会让副本以为自己给自己续期 |
| `isReplicaOrphaned(now, tol)` | `isExpire(now)` | 同上 `:74-76`；接口声明在 `naming/.../core/v2/client/Client.java:138` | 过期判据挂在 Client 上，且只判副本 |
| `ClientSession.connectionId`、两张反向索引的 `Set<String> connectionIds`、`ClientSyncData`/`ClientVerifyInfo` 的 `connectionId` 字段 | `getClientId()` 返回 `connectionId`；载荷里叫 `clientId` | 同上 `:52`；Nacos `core/v2/client/ClientSyncData.java:34` | 值就是一个 TCP 连接的 id，"client" 正是让 harbor 早期退回按 IP 匹配的那个词；载荷只在 harbor 节点之间流转，故一并改名，见 §3.10 |
| `recalculateRevision()` | `recalculateRevision()` | `AbstractClient.java:203-207`、`ConnectionBasedClient.java:80-82` | 同名；语义有偏离，见 §3.1 |
| `PushDelayTaskEngine` | `PushDelayTaskExecuteEngine` | `naming/.../push/v2/task/PushDelayTaskExecuteEngine.java` | 服务级合并的推送延迟引擎 |
| `ConnectionLifecycle.cleanup()` | `clientDisconnected(clientId)` | `naming/.../core/v2/client/manager/impl/ConnectionBasedClientManager.java:96`、`:105-118` | 关闭动作的唯一事务入口 |
| `HealthCheckManager` 的巡检 | `ExpiredClientCleaner.run()` | 同上 `:158-174` | 巡检只是「复用同一个事务入口」，不另写一套清理 |
| `ConnectionManager`（连接记录 + 活性戳 + client session + 推送出口，四合一） | `core/remote/ConnectionManager`（**只有连接注册表与活性**） | `core/remote/ConnectionManager.java:63`（`connections`）、`:104`（`register`）、`:153`（`unregister`）、`:245-248`（`refreshActiveTime`）、`:260-262`（每 3s 的巡检只 `doEject` 连接） | 名字撞了，**层级与数据域都不同**：见 §2.5 与 §3.9 |
| —（同上，语义那一半） | `ConnectionBasedClientManager.clients`，走 `ClientManager` 接口 | `naming/.../core/v2/client/manager/impl/ConnectionBasedClientManager.java:52`，且 `:49` 是 `extends ClientConnectionEventListener` | 「连接」与「客户端」在 Nacos 分属两个模块，靠事件解耦 |
| —（同上，推送那一半） | `Connection implements Requester` + `RpcPushService` | `core/remote/Connection.java:31`；`core/remote/RpcPushService.java:50`（`pushWithCallback`）、`:100`（`pushWithoutAck`） | 写出能力长在连接对象上，下推另有统一入口 |
| `ClientSyncData` | `ClientSyncData`（**同名同职责**） | `naming/.../core/v2/client/ClientSyncData.java:30` | 复制单元是 client 级；Nacos 字段为 `clientId + attributes + namespaces/groupNames/serviceNames + instancePublishInfos + batchInstanceData`（`:34-46`），harbor 把三段式服务名并成一个 `serviceKey`；订阅关系两侧都**不出网**（见 §3.8） |
| `ClientVerifyInfo` | `DistroClientVerifyInfo` | `naming/.../consistency/ephemeral/distro/v2/DistroClientVerifyInfo.java` | 对账只带 `(clientId, revision)` 两个字段 |
| `onSnapshot()` 返回 `List<ClientSyncData>` | `getDatumSnapshot()` → `ClientSyncDatumSnapshot` | `DistroClientDataProcessor.java:282-294` | 启动加载 = 一次性全量快照，逐 client 构造 |
| `WireHarborNodeTransport` | `DistroClientTransportAgent` | 同上目录 `DistroClientTransportAgent.java:67/91/115/141` | 节点间传输是一个可替换的 agent 薄层 |
| wire 的 `Request.request` 一元 + bidi 推送 | `nacos_grpc_service.proto`：`service Request{rpc request(Payload) returns(Payload)}`、`service BiRequestStream{rpc requestBiStream(stream…) returns(stream…)}` | `api/src/main/proto/nacos_grpc_service.proto:39`、`:45` | 注册中心 RPC 面只有这两种形态，见 §5 |
| `conn_cleanup` 写入父通道属性 → 调用上下文 | `Connection.getMetaInfo().getConnectionId()` | `ConnectionBasedClientManager.java:101` | 连接 ID 要能在每一次调用中被业务侧拿到 |

## 2. 同源决策：照做的机制与数值出处

| 机制 | Nacos 值与出处 | harbor 值与出处 |
|---|---|---|
| 巡检节拍 | `DEFAULT_HEART_BEAT_INTERVAL = 5s`（`api/.../common/Constants.java:189`；`SwitchDomain.java:47`） | `CHECK_INTERVAL_MS = 5_000`（`HealthCheckManager.java:43`） |
| 不健康阈值（保留但标记） | `DEFAULT_HEART_BEAT_TIMEOUT = 15s`（`Constants.java:185`；`UnhealthyInstanceChecker.java:60-65`） | `INSTANCE_UNHEALTHY_TIMEOUT_MS = 15_000`（`HealthCheckManager.java:66`） |
| 过期删除阈值 | `DEFAULT_CLIENT_EXPIRED_TIME = 3min`（`naming/.../constants/ClientConstants.java:57`） | `INSTANCE_TIMEOUT_MS = 180_000`、`SYNCED_SESSION_TIMEOUT_MS = 180_000`（`:59`、`:75`） |
| verify 周期 | `DEFAULT_DATA_VERIFY_INTERVAL_MILLISECONDS = 5000`（`core/.../distro/DistroConstants.java:54`） | `VERIFY_INTERVAL_MS = 5000`（`DistroProtocol.java:53`） |
| 启动加载重试 | `DEFAULT_DATA_LOAD_RETRY_DELAY_MILLISECONDS = 30000`（`DistroConstants.java:68`） | `LOAD_DATA_RETRY_DELAY_MS = 30_000`（`:56`） |
| 推送失败重试固定延迟（非指数） | `DEFAULT_PUSH_TASK_RETRY_DELAY = 1000`（`naming/.../constants/PushConstants.java:45`） | `RETRY_DELAY_MS = 1000`（`PushDelayTaskEngine.java:44`） |
| 延迟合并的「同键合一任务 + 到点重读」 | `NacosDelayTaskExecuteEngine.addTask` → `newTask.merge(existTask)`（`common/.../task/engine/NacosDelayTaskExecuteEngine.java:119-124`）；`DistroDelayTask.merge` 保旧动作（`core/.../distro/task/delay/DistroDelayTask.java:61-69`） | `pending.computeIfAbsent` + 到点 `buildClientSyncData` 重读当前全量幂等推（`DistroProtocol.java:195-217`） |
| verify 不一致 → **owner 定向重推**（不是去 peer 拉） | `syncToTarget(distroKey, ADD, targetServer, 0L)`（`naming/.../distro/v2/DistroClientDataProcessor.java:120`） | `resyncToPeer(peer, clientIds)`（`DistroProtocol.java:422-442`） |
| 健康判定权只属于持有连接的节点，副本只显示不判定 | `isResponsibleClient(client)` 随两个事件外发（`ConnectionBasedClientManager.java:113-116`） | `healthTransitionNotifier` + 15s 档仅对 native 生效（`ServiceStorage.java:383`、`:435`） |
| 广播「当前全量 + 幂等收敛」，无应用层 ack | `NotifySubscriberResponse extends Response`，**无任何字段**（`api/.../naming/remote/response/NotifySubscriberResponse.java:26`） | 每次重读当前全量，不缓存旧 payload（`PushDelayTaskEngine` 类注释） |
| 空闲保活 = `HealthCheckRequest`，触发条件是「闲置够久」而非固定定时器 | 默认 `connectionKeepAlive = 5000`（`common/.../grpc/DefaultGrpcClientConfig.java:224`）；`reconnectionSignal.poll(keepAlive)` 超时后比对 `lastActiveTimeStamp` 才发（`common/.../remote/client/RpcClient.java:353-359`） | 5s 巡检 + 90s 连接静默判死（`HealthCheckManager.java:43/51`） |
| HTTP/2 PING 只是「无应用层心跳时」的兜底 | `channelKeepAlive = 6*60*1000`（`DefaultGrpcClientConfig.java:238`，用于 `GrpcClient.java:220-221`） | 不依赖 PING 做活性判定，PING strike 语义归 core |
| 只有 owner 才对外 advertise 对账数据 | `getVerifyData()` 内 `if (clientManager.isResponsibleClient(client))` 才入列（`DistroClientDataProcessor.java:296-310`） | `runVerifyTask` 只遍历 `allNativeClientSessions()`（`DistroProtocol.java:326-330`） |
| 只有 ephemeral，不做持久实例 | 持久实例走 Raft CP（`consistency` 模块），naming v2 的 `ConnectionBasedClient.isEphemeral()` 恒 true（`ConnectionBasedClient.java:57-59`）；快照与对账构造时 `!client.isEphemeral()` 直接跳过（`DistroClientDataProcessor.java:286`、`:302`） | 只实现 AP 线，见 §5 |

## 2.5 节点内的两种拓扑角色：分片层与全集群层

判据只有一条：**这个事实除了本节点，别的节点能不能答。** 能答的必然全集群复制，不能答的就是分片。

| 容器 | 角色 | 写入口 | 为什么归这一类 |
|---|---|---|---|
| `ConnectionManager.connections`（`:37`） | 分片 | `register`（`:55`，仅 `ConnectionSetupRequest` 之后） | 它不是「跟着分片走」，它就是分片归属这件事的定义 |
| `ConnectionRecord.lastActiveTime`（`:212`，`AtomicLong`） | 分片 | `touch`（`:86` → 记录自身 `:215`） | 活性只有持有 TCP 的节点观测得到；看门狗 `removeStaleConnections`（`:108`）只摘这一层，判据长在记录上（`isStale` `:225`） |
| `ServiceStorage.subscriberIndexes`（`:59`） | 分片 | `addSubscriber`（`:208`，写入在 `:211`） | 订阅只在本地；副本永不进这里（§3.8 收回的就是这一条） |
| `clientSessions` 的 **native** 部分（`:43`） | **本体** | `register`（`:55`） | 既非备份也非分片：我这个 shard 的权威写侧就在这里发生 |
| `clientSessions` 的 **synced** 部分 | 备份 | `putClientSession`（`:168`） | 别人 shard 的只读副本，供本地答路由查询 |
| `ServiceStorage.publisherIndexes`（`:52`） | 全集群 | `:127`（本地注册）+ `:655`（副本落地） | 任何节点都要能回答任意服务的查询 |
| `ServiceStorage.serviceDataIndexes`（`:68`） | 全集群（派生缓存） | 随写路径失效重建 | 由全集群数据算出，自然也是全量域 |

于是基数关系是确定的：`|connections| = 我的 shard 大小`，而 `|clientSessions| = 全集群连接数 ≥ 前者`——两张表共用 `connectionId` 键空间，**域却不同**。

结构上有一条可点开的铁证，也是这两类的分界：`register` 既写连接表也写会话表，而 `putClientSession` **只写会话表**，并且 `touch` 对非本地持有的连接直接跳过。好处是分片归属永不被备份污染（绝不会把别人的连接误当成自己的去推送）；代价是活性层看不见副本——owner 节点一旦死掉，它那批备份没人能按活性收掉，只能由 `reapStaleSyncedClients`（`ServiceStorage:698`）配 `SYNCED_SESSION_TIMEOUT_MS`（`HealthCheckManager:75`）用「owner 沉默满一个过期窗」单独立一档收尸。这笔账在 §3.9 里也记了一次。

两条边界值得钉住，免得「备份型」被误读成多主可写：**写只发生在 owner**，同步方向永远是 owner 外推，副本只读；**分片键是 TCP 落点而不是 `hash(clientId) % members`**，所以没有 rebalance——客户端重连即自然迁移 shard，其账单由上面那档收尸机制偿还。

Nacos 是同一个形状，但拆在两个模块：一张 `clients` 表同时装两种角色（`ConnectionBasedClientManager:52`），入口分而合——本节点连接走 `clientConnected(clientId, attributes)`（`:73-77`，内部 `clientFactory.newClient`），备份走 `syncClientConnected(...)`（`:89-93`，内部 `newSyncedClient`），**两者最后汇入同一个 `clientConnected(Client)` 的 `clients.computeIfAbsent`**（`:80-87`），角色只留一个出生即 `final` 的 `isNative` 位（`ConnectionBasedClient:37`）。而分片那一层在 Nacos 属于传输模块（`core/remote/ConnectionManager:63`，活性是 `Connection` 对象上的字段，`ConnectionManager:245-248`）——这正是 §3.9「同名不同层」的实质差异所在。

## 3. 刻意偏离清单

### 3.1 revision：副本侧用自增计数器，harbor 用内容指纹

Nacos 的**基类**是内容哈希：`AbstractClient.recalculateRevision()` → `revision.set(DistroUtils.hash(this))`（`AbstractClient.java:203-207`），`DistroUtils.hash` 逐实例取 `Objects.hash`（`naming/.../utils/DistroUtils.java:71-97`）。但**连接型客户端把它覆盖了**：`ConnectionBasedClient.recalculateRevision()` → `revision.addAndGet(1)`（`ConnectionBasedClient.java:80-82`）——对连接模型而言 revision 是**每次变更 +1 的计数器**；内容哈希路线只保留给 `IpPortBasedClient`（`DistroUtils.java:72-74` 对其余类型直接返回 0）。

harbor 反其道：把 Nacos 用在另一类客户端上的内容指纹思路搬到连接模型（`ClientSession.java:175-187`，XOR 逐条目哈希）。

理由：verify 的语义是「两节点的数据是否一致」，计数器回答的是「变更发生过几次」。实例增了又删回原样，计数器已前进，peer 会报 mismatch 并触发一次内容完全相同的重推——correctness 不受损，但白耗一轮同步。内容指纹对这种情况判一致。**代价**：32 位哈希理论存在碰撞漏检；XOR 对「同服务两个实例互换」不敏感（同 key 同端口同权重会被抵消），因此条目哈希里带 `ip/port/healthy`，且副本与原生两条路径必须用同一套 XOR 规则（这正是 `:162-166` 注释强调顺序无关的原因）。

### 3.2 `lastBeat` 不进 revision，`healthy` 进

`lastBeat` 是只有 owner 能读的墙钟；副本上的值是推送时被冻结的（Nacos 同样不逐心跳转发）。若折进哈希，native 与 synced 副本天然不等 → verify 每轮都误报并启动无意义重推。反之 `healthy` 是**被复制的内容**：owner 无实例变更地翻转判定时，必须可被 verify 检出，否则丢一次推送就让两节点永久分歧。见 `ClientSession.java:168-173`。

### 3.3 合并窗口比 Nacos 更激进：200ms vs 1000ms / 500ms

Distro 同步延迟 Nacos 默认 `1000ms`（`DistroConstants.java:33`），推送延迟 `500ms`（`PushConstants.java:31`）；harbor 两处都用 `200ms`（`DistroProtocol.java:59`、`PushDelayTaskEngine.java:41`）。取舍：注册中心的核心 SLA 是「变更多快被看到」，harbor 数据量小（client 级全量载荷），收敛优先于合并率。**代价**：突发批量注册下推送次数放大，靠父通道级 bidi 单流扛住。若将来压测显示推送线程饥饿，回滚动作就是把两个 200 改成 Nacos 的 1000/500，属纯常量改动。

### 3.4 不做 payload 缓存重发（删掉 `PushRetryManager`）

曾按「可靠投递」直觉实现过一个重试管理器，读源码后发现 Nacos 的 `NotifySubscriberResponse` 是**空响应**（`api/.../naming/remote/response/NotifySubscriberResponse.java:26`，除 `Response` 基类字段外无任何成员）——根本不存在应用层投递信号可供重发决策。缓存旧 payload 重发在「到点重读全量」模型下是**倒退**：乱序的一帧会把订阅者刷回过期视图。故删除，改为「连接在则 skip、异常则固定延迟重排一次新的全量推」。

### 3.5 不做 `protectThreshold`

值得单列，因为核对源码后结论反而**变轻**了：Nacos 的 `ServiceMetadata.protectThreshold` 默认 `0.0F`（`core/v2/metadata/ServiceMetadata.java:44`），而在 v2 里它的唯一使用点是 catalog 侧把「是否处于保护态」渲染成一个字符串——`isProtectThreshold = healthyCount * 1.0 / ipCount <= threshold`（`core/CatalogServiceV2Impl.java:212-215`，调用者 `:165`、`:196`）。实例选择路径（`ClientService` / `ServiceStorage`）里**没有任何**「健康实例过少就返回全量（含不健康）」的保护性回退——那是 1.x 的路由器行为，v2 已退化成展示字段。

所以 harbor 不做它，与 Nacos v2 的实际行为一致；真正与 1.x 直觉冲突的是 harbor 刚定下的「如实标 unhealthy」：一个要兜底返回、一个要不健康即不可用。harbor 选后者，语义更硬，代价是极端误判场景没有兜底。**若将来引入客户端侧的降级路由（或 Nacos 把保护态做回选择路径），这条要重新评估。**

### 3.6 不可靠的机制删掉，不加补救参数

三例：`connectionId` 的 clientIp 兜底、bi-stream 里的 `UUID` 兜底、`connectionId` 的事后可变（改构造注入 + `final`）。共同点是该值「取不到时怎么办」无论怎么答都可能把一条连接的生命周期记到错误实体上，静默错位比失败更难查。所以直接要求它必然存在（`HarborServer` 相关分支、`da7ee4ea`/`597cb319`），代价是任何未预料的时序会显式抛错而非降级。

### 3.7 连接属性传播做成可配置键集，wire 层不解释语义

Nacos 不需要这个：它的调用上下文本身就在 `Connection` 对象上。harbor 的 wire 层要在 HTTP/2 子通道上拿到父（TCP）通道属性，最初把 `CONNECTION_ID` 硬编码进 dispatcher——那等于让通用传输层持有业务概念。现改为 `WireServer.addConnectionAttributeKey(String)` 注册 + `mergeConnectionAttributes()` 只搬运不解释（`d8c04653`，约束见 `691e77b3`）。

### 3.8 订阅关系不出网（曾复制，已按 Nacos 收回）

Nacos 的复制载荷由 `AbstractClient.generateSyncData()` 生成（`AbstractClient.java:141-173`），**只遍历 `publishers`**（`:153-168`）——订阅是 `AbstractClient:49` 上的本地 map，从不过网；触发侧同理：harbor 只在 register/deregister/batch 与健康翻转时请求同步（`HarborServer.java:479/484/518`、`:127-128`），订阅本身不触发任何同步。附带一处同名对照：Nacos 把 revision 作为 client attribute 搭在同步载荷里（`:171` `addClientAttribute(REVISION, getRevision())`），harbor 则是 `ClientSyncData` 的显式字段。

harbor 早期版本在载荷里多带了一个 `subscriberKeys`，并计入 `hasContent`。核对本表时把它删掉了，因为两条害处都是实打实的：

1. **污染本节点的推送目标索引**。副本落地时会把自己的 clientId 写进 `subscriberIndexes`（`ServiceStorage.getSubscriberConnections` 的语义因此变成谎话——「本节点当前订阅者」里混进了写不到的连接，`PushDelayTaskEngine` 每一轮推送都要空查一次并记一条 "connection gone"）。
2. **造出没有删除路径的副本壳**。纯订阅副本没有实例，beat 两档永远看不见它；而连接关闭时的 DELETE 判据只看 `serviceKeys`（`ConnectionLifecycle.java:92`），于是这种壳只能等 180s 孤儿回收兜底。

**实测证据**（3 节点集群 19848/19849/19850 + provider/consumer 各起一次）：provider 连到 19848 注册 2 个服务，另两节各自日志出现一条 `applied client sync: <connId> (publishers=2)`（跨节点发现不受影响）；consumer 只订阅，其连接在另两节点上**零**条同步记录，全集群 `publishers=0` 出现 **0** 次、`distro delete client` **0** 次——即「无内容的壳」这一形态在集群里已不存在。

`ConnectionLifecycle` 的 DELETE 判据因此不需要扩展看订阅：没有订阅被复制，就没有需要撤销的订阅副本。回归由 `SubscriptionStaysLocalTest` 钉住（载荷不含订阅 / 就算被请求同步也只会是 DELETE / 副本永不进推送索引），mutation check 双注入验证过非假绿。

### 3.9 `ConnectionManager`：Nacos 的三层，harbor 的一层（**决定不拆**）

Nacos 把这件事切成三层，中间用事件解耦：

- **连接与活性**：`core/remote/ConnectionManager` 只持 `connectionId → Connection`（`:63`、`:104`、`:153`）与活性刷新（`:245-248`），外加连接治理（按 label 计数、`loadCount`/`redirect` 迁移、ejector 主动踢）；每 3s 的巡检只 `runtimeConnectionEjector.doEject()` 处理**传输层连接**（`:260-262`）。
- **客户端语义**：`ConnectionBasedClientManager.clients: clientId → ConnectionBasedClient`（`naming/.../ConnectionBasedClientManager.java:52`），类本身 `extends ClientConnectionEventListener`（`:49`），靠 `ClientReleaseEvent`/`ClientDisconnectEvent` 与传输层握手。
- **下推出口**：写出能力长在连接对象上（`core/remote/Connection.java:31` 是 `abstract class Connection implements Requester`），服务端下推统一走 `core/remote/RpcPushService.pushWithCallback/pushWithoutAck`（`:50`、`:100`）。

harbor 把四样东西装进一个类：连接记录（`ConnectionManager.java:37`）、活性时钟（长在记录自己身上：`ConnectionRecord:212` + `touch` `:215`，由 `:86` 转发）、client session 表（`:43`，`putClientSession` `:168` 等于让 Distro 把手伸进传输层注册表）、推送出口（`pushToConnection` `:131` 直接 `pushSubject.onNext`）。

**判断是不拆**。harbor 只有 49 个文件，模块边界已经能由类名表达，再拆一层 `ClientSessionManager` 换来的是类图相似而非正确性，代价是要动 `DistroProtocol`/`ServiceStorage`/`HarborServer` 三处引用面。

但这条偏离的代价不是零，而且要分清哪一半已经还了。原来活性戳存放在与连接表并列的第二张 map 里，`putClientSession` 从不写它——两张表必须同步是条隐形契约，漏一处就泄漏。现已把时钟并进 `ConnectionRecord`，只剩一张表，**这条漂移由构造消灭了**。没消掉的是另一半：副本压根没有 `ConnectionRecord`，所以看门狗（只看活性）结构性地看不见副本——「owner 死后副本无人收」那个漏正是这个形状长出来的，今天靠 `reapStaleSyncedClients` + `SYNCED_SESSION_TIMEOUT_MS` 单独立一档兜住（`HealthCheckManager.java:75`）。留此记录，是为了下次有人想说「顺手再加一张表」时能看到：合层的账是按档叠加还的。

**同构之处也值得记一笔**：`removeStaleConnections` 只摘活性层、把 session 留给 `ConnectionLifecycle` 单入口收尾，与 Nacos「`doEject` 只处理连接、语义清理走 `clientDisconnected`」是同一个分层判断。

**重评触发条件**：一旦要做连接治理（按 label 限流、负载迁移、主动踢连重平衡），或者要让多协议共用同一张连接注册表，把传输层拆出来才有真实收益——那时再拆。

### 3.10 全仓改叫 connectionId，包括 Distro 载荷

Nacos 在 `ClientSyncData` 与 `DistroClientVerifyInfo` 里把主键字段叫 `clientId`（`ConnectionBasedClient.getClientId()` 返回的其实也是 `connectionId`，`:52`），harbor 连同自己的载荷模型一起改叫 `connectionId`：`ClientSession.connectionId`、两张反向索引的 `Set<String> connectionIds`、`ClientSyncData.connectionId`、`ClientVerifyInfo.connectionId`、`DistroVerifyResponse.mismatchedConnectionIds`。

改名没有兼容代价，因为这两个类是**节点之间**的 Distro 载荷（JSON 装进 `Payload.body`，类型名 `DistroSyncRequest`/`DistroVerifyRequest` 都是 harbor 自己的），nacos-client 既不发也不读；harbor 集群两端同步演进即可。与 nacos-client 互通的那一面是 `NotifySubscriberRequest`/`InstanceRequest` 那批，字段名一律照旧。

值得这个名字的理由是硬的：`client` 一词在读者心里默认指进程或主机，harbor 早期就因此留过 `connectionIdByClientIp` 之类的按 IP 兜底，而同一台机器起多个进程时那会互相覆盖（§1 的第一条不变式）。值是一个 TCP 连接的 id，就叫它 connectionId。

同类的名字取舍还有两处：副本背书时钟采用 Nacos 的 `lastRenewTime`（消除读差），但推进它的动作保留 `markOwnerConfirmed()` —— `renew` 不带主语，容易被读成「副本自己续期」，而那正是这条不变式要防的误判；字段与访问器的注释只留在使用的当下有用的事实，命名史与 Nacos 对照一律收在这里。

## 4. 测试即语义注解

`jaws-harbor` 的 57 个用例里，主干测试类各自钉住一条 Nacos 语义，类名就是命题：

| 测试 | 钉住的语义 |
|---|---|
| `EphemeralHealthTierTest` | 15s 标不健康且保留、180s 才删的两档结构 |
| `SyncedHealthAuthorityTest` | 健康只由持有连接的节点判定，副本只显示（§2/§3.2） |
| `SyncedSessionReclamationTest` | 孤儿副本回收判据是 owner 沉默，不被本地改动赦免 |
| `SubscriptionStaysLocalTest` | 订阅不出网：载荷不含订阅、副本永不进推送索引（§3.8） |
| `WatchdogClosureTest` | 快照必须在摘除 session 之前取，否则漏发 DELETE |
| `NotifyOnChangeOnlyTest` | 只为真实数据变化播报；巡检与退订不触发全量重推 |
| `PushDelayTaskEngineTest` | 服务级合并 + 到点重读当前全量 + 连接已断则 skip |
| `HarborDistroProtocolTest` / `HarborDistroClusterTest` | verify 不一致 → owner 定向重推的修复方向 |

## 5. 已知边界（不是偏离，是尚未做）

- **AP 线之外的东西一概不做**：持久实例与 Raft CP、配置长轮询、鉴权/console/k8s-sync 都不在 harbor 范围内（产品面，研读优先级低于机制层）。
- **节点间全是一元**：与 Nacos 同形——Nacos 注册中心自己的 gRPC 服务面只有 unary `request` + 一条 bidi 连接流（`nacos_grpc_service.proto:39`、`:45`），仓库里带 streaming 的只有 vendored 的 Istio/MCP proto（`istio/src/main/resources/proto/mcp/v1alpha1/mcp.proto:175`、`:299`），在 `istio/src/main/java` 里没有任何 `rpc` 实现引用；连 jraft-core 1.3.14 的 `installSnapshot` 都是分块多请求而非 gRPC 流。规模化风险点在启动快照——万级 client 时单包 JSON + 5s 一元超时会痛，届时 client-stream 是自然形态，属「用对原语超越 Nacos」而非补角。
- **已决并落地**：纯订阅连接的复制/回收不对称选了 B——订阅关系整体退出复制载荷，向 Nacos 靠齐（详见 §3.8，含 3 节点集群实测证据）。
- **已订正**：`ConnectionLifecycle` 的 Javadoc 曾把对标对象写作 Nacos 的 `ConnectionManager` + `ClientConnectionUnregisterEvent`（该符号在 Nacos 不存在）。本次随本文一并改为真实的 `ConnectionBasedClientManager.clientDisconnected(String)`（`:105-118`：`clients.remove` → `release()` → 以 `isResponsible` 发 `ClientReleaseEvent`/`ClientDisconnectEvent`），并点明 Nacos 自己的看门狗 `ExpiredClientCleaner` 也复用这同一个入口——正是本类要编码的性质。

## 6. 维护纪律

1. 新增或改动 harbor 机制时，必须在 §1/§2/§3 中落一行，且 Nacos 出处要到 `路径:行号` 粒度——不接受「Nacos 也是这么做的」这种无出处断言。
2. 常量对齐就对齐 Nacos 默认值；要对齐得不一样，必须进 §3 写清代价与回滚动作。
3. 命名优先采用 Nacos 的概念名。改名若降低了「读完 harbor 能读 Nacos」的顺滑度，就是负收益。
4. 每次版本升级重核一遍行号——Nacos 行号会变，本文的信用建立在「可点开就成立」上。
