# Nacos Config：从服务端到客户端的全链路设计（nacos@master 3.x 研读）

## 0. 这份文档要解决什么

本篇是 nacos **config 域的设计存档**：从「谁写 DB」到「客户端怎么收到变更」整条链，钉成带行号的剖析，与命名客户端视角的 [harbor-vs-nacos.md](harbor-vs-nacos.md) §8 附录互补。

行号锚定本地 `~/github/nacos`（3.x 线 master），每一句可 checkout 复核。

## 1. 全景一图

```
写路径   Console(React UI + Controller) / SDK ──► ConfigOperationService
                │                                       │ 写成功
                ▼                                       ▼
        ConfigInfoPersistService ──── DB（唯一权威）── ConfigDataChangeEvent（一个事件）
                                                          │ NotifyCenter 单线程顺序回调两个订阅者
                                          ┌───────────────┴────────────────┐
                                DumpService（本地订阅者）          AsyncNotifyService（集群订阅者）
                                本节点 dump：拉DB→写盘→刷摘要        门铃 gRPC → peers 各自查 DB dump
                                                          │
                                          兜底：DumpChangeConfigWorker（30s 水位轮询）
                                                + DumpAllTask（6h 全量）
读路径   客户端拉取 ─► 查询责任链 ─► FormalHandler：内存出摘要 + 磁盘出正文
通知     ConfigCacheService.dump 方法尾部 ─► LocalDataChangeEvent ─► RpcConfigChangeNotifier 推「信号」，客户端回拉
```

## 2. 写路径：一条权威，一个事件，两个订阅者

- **写入口收敛**：Console 不是独立写路径——它只是 React Web UI 加一层 Controller，与 SDK 的写请求共同汇入 `ConfigOperationService` → `ConfigInfoPersistService` → DB。**写 DB 在 config server 模块，DB 是唯一权威**。
- **SDK 侧的写半链**（`NacosConfigService` → `ClientWorker` → 服务端 handler）：
  - `publishConfig / publishConfigCas`（`NacosConfigService:187-211`）收敛到 `publishConfigInner:376`：本地参数校验 → **客户端 filter chain**（加密在此改写 content 并回填 `encryptedDataKey`）→ `ClientWorker:1430` 组装 `ConfigPublishRequest`，tag/betaIps/type/encryptedDataKey 等走 **`additionalParams` map 扩展位**——proto DTO 保持稳定、新功能加键，与命名面「类简名 + body」同一契约思路 → **一元 gRPC，与 listen 复用同一条 SDK 面连接**。
  - **软返回**：publish 不抛，失败/异常都 warn + `false`；`publishConfigCas(casMd5)` 是乐观锁原语，CAS 不匹配即 false。
  - 服务端 `ConfigPublishRequestHandler.handle()` 挂四个声明式切面——`@NamespaceValidation`、`@TpsControl("ConfigPublish")`（写限流独立配额桶）、`@Secured(WRITE, CONFIG)`（鉴权按 action 分级）、`@ExtractorManager.Extractor`（审计取参）——然后解参组 `ConfigForm` 汇入 `ConfigOperationService`。
  - **写者不享受特权**：publish 不回填本地 `CacheData`、不直通——SDK 自己也要走完「信号推 → 回拉」这一圈才看到新值。权威只有服务端一份，写读彻底分离。
- **发布方有四个，不止 publish**：`ConfigOperationService.java:159/164/255`（写入）、`ConfigCloneService:154`（克隆）、`ConfigControllerV3:261`（删除）、`:421/:624`（Beta 灰度）——全部只发同一个 `ConfigDataChangeEvent`。发布方不认识任何消费者，只陈述「这条配置变了」。
- **一个 gate**：`ConfigChangePublisher:38` `if (isEmbeddedStorage() && !standalone) return;`——内嵌 derby 集群根本不走这条事件链（数据由 JRaft 复制到本地，§8），**DB 模式与单机 derby 共用本链路**。
- **事件基础设施**：自研 NotifyCenter——`DefaultPublisher:72` 内部就是 `ArrayBlockingQueue`，`NotifyCenter:73` 的 `ringBufferSize=16384` 只是对 LMAX Disruptor 的术语致敬（预分配、无锁序列号、消除伪共享那套都没有；低频事件场景不需要）。每个事件类型一个 publisher，**单线程顺序回调**订阅者，各自内部再异步化。
- **两个订阅者，顺序无保证也正确**：`DumpService:146-150` 订阅自己触发**本节点收敛**（拉 DB→写盘→刷摘要）；`AsyncNotifyService:95-96` 订阅自己触发**集群传播**。互不依赖的根源是门铃不带正文（§4）——若通知携带正文，就得等本地 dump 完成，两个订阅者被迫排队。
- **写后到客户端的完整时序**：t0 写 DB → t1 事件入队（微秒）→ t2 `DumpProcessor` 从 DB 读出 → t3 `saveToDisk` 落盘 → t4 `ConfigCacheService.dump` 刷新摘要 → t5 同一方法尾部发布 `LocalDataChangeEvent`（`ConfigCacheService:336` 等四处）→ t6 各通知腿推**信号**。
- **扩展性先例**：给这条链加新职责 = 加一个订阅者，动不到发布方。Istio 推送就是这么挂上来的（`IstioConfigChangeEvent extends ConfigDataChangeEvent`，配置带 Istio tag 时多发一个子类事件）。

## 3. 开机初始化：两端各自先做什么

**服务端**——先看 gRPC 服务器在哪起（不熟 gRPC 的人最容易困惑的一点）：

- `GrpcSdkServer` 与 `GrpcClusterServer` 是**两个 Spring bean、两个独立端口**（对应 §2 的 SDK 面与集群面）。二者经 `BaseRpcServer.start()`（**`@PostConstruct`，随 bean 初始化即启动**，日志「Nacos GrpcSdkServer Rpc server starting at port …」）调 `BaseGrpcServer.startServer():91-99`：`new MutableHandlerRegistry()` → `addServices(...)` → `NettyServerBuilder.forAddress(主端口 + rpcPortOffset())`——SDK 面 +1000、集群面 +1001。**这不是 Tomcat/Servlet 容器：gRPC 服务器是 grpc-netty 自己监听的另一条 TCP 端口**，`fallbackHandlerRegistry(:112)` 挂上 handler 表，`:182` 注册 `AddressTransportFilter`——`transportReady` 时按 TCP 铸 connectionId 塞进连接属性（每个 call 经 context 携带，§2 的注册表靠它），`transportTerminated` 时注销。
- RPC handler 无需手工装配：`RequestHandlerRegistry` 在 `ContextRefreshedEvent` 按类简名自动注册（见 §4）。

数据面的开机动作在 `DumpService` 抽象基类 + 两个 `@PostConstruct init()` 形态（`service/dump/`）：

- `ExternalDumpService.init()`（生产外置 DB）：直接 `dumpOperate()`——全量 `dumpAll` 打底，再拉起兜底调度（§4 的 DumpAllTask 与 DumpChangeConfigWorker 都从这里挂上）。它的 `canExecute() = memberManager.isFirstIp()`：**周期全量兜底只在集群首成员上跑**，不是每个节点同时扫全库。
- `EmbeddedDumpService.init()`（内嵌 derby）：单机同样直接 `dumpOperate()`；集群则**订阅 JRaft 的 leader 元数据**（`waitDumpFinish` 闩 + `EXTEND_NEED_READ_UNTIL_HAVE_DATA`），等 raft 状态机追平、有数可读才开始 dump——**开机顺序被共识就位卡着**，§8「内嵌选共识」在启动期的表现。

**SDK 侧**——`NacosConfigService` → `ClientWorker` → `ConfigRpcTransportClient`。gRPC 连接**不是构造时就建好**，而是监听/请求第一次用到时才 `ensureRpcClient(taskId)`（`ClientWorker:1240`，主连接组 `"0"` 经 `getOneRunningClient():1427`）：`RpcClientFactory.createClient(uuid + "_config-" + taskId, ...)`（`:1247`——config 自起、与 naming 面各建各的、按 taskId 分组共享，ServerCheck 握手契约同 [harbor-vs-nacos.md](harbor-vs-nacos.md) 附录 8.3）→ `initRpcClientHandler` 挂上 server-push 回调 → **`rpcClient.start()` 同步走完附录 8.3 全套握手**（对 server list 选的地址、端口 = 主端口 + 1000：channel → `ServerCheckRequest` 拿 connectionId → bidi 流上 `ConnectionSetupRequest` → SetupAck），失败换下一个地址。之后 config 拉起一个单线程 `listen-executor`（`ClientWorker:895`）+ `listenExecuteBell`：

- **事件铃驱动，不是定时扫**：5s 有界 poll 只是心跳兜底，真正的节拍来自摇铃——`addListener` / 取消监听 / 内容刷新 / 收到 gRPC 变更通知都 `notifyListenConfig()`（`:187/:214/:247/:981/:1129`）。
- `executeConfigListen()`（`:931`）每圈三件事：与服务器一致的 cache 只做 `checkListenerMd5()`（§7 消费位点的「搭车重试」由此驱动）；不一致的按 taskId 分组批量回拉（`checkListenCache → syncReceive`）；`needAllSync` 到点周期全量对账。
- 一个线程统一了「推送 → 回拉 → listener 消费」的后半链——通知腿只摇铃，重活都在这个 executor 里串行做。

## 4. 集群传播：门铃是主要手段，轮询是兜底，全量保最后

- **门铃链**：`AsyncNotifyService:107` `allMembersWithoutSelf()` → `:142` `executeAsyncRpcTask` → `:170` `configClusterRpcClientProxy.syncConfigChange` → `ConfigClusterRpcClientProxy:53` `asyncRequest`——走 **clusterRpcClient（集群端口）**，不是 SDK 面。请求仍只带 `dataId/group/tenant/lastModified` 元数据、**不带正文**；对端收到后 `dumpService.dump` 从共享 DB 拉这一条。门铃只催不送：它影响传播延迟，正确性由「最终查 DB」保证。
- **对端注册点（搜不到显式调用）**：`ConfigChangeClusterSyncRequestHandler` 是 `@Component` + `@InvokeSource(LABEL_SOURCE_CLUSTER)`（只许集群来源连接调用），由 `core/remote/RequestHandlerRegistry` 在 `ContextRefreshedEvent` 按**请求类简名**自动注册——grep `addRequestHandler` 不会有结果，搜不到注册点不等于没装配。
- **门铃不是 fire-and-forget**：`MAX_COUNT=6`、`MIN_RETRY_INTERVAL=500ms`、`INCREASE_STEPS=1000ms` 退避重试，非健康成员延迟补发（`:155-158`）。稳态下门铃亚秒送达，兜底轮询几乎空转（水位早已对齐）——**主要同步手段是门铃，轮询只是兜底**。
- **兜底轮询 `DumpChangeConfigWorker`**：按 `gmt_modified` 水位 + cursor 分页 `findChangeConfig`，逐条比对「DB 时间戳 > 本地 || DB md5 ≠ 本地」，变了就 dump，**顺带清理已删除的配置**。调度形态值得抄：首轮 `DumpService:247` 在 0~30s 内随机铺开节点相位，`finally` 自排固定 30s（`:181-183`，单次 `schedule` 语义 = 完成时刻 + delay）；`catch(Throwable)` + `finally` 自排是**结构性免疫**——绕开 JDK `scheduleWithFixedDelay` 「单轮异常静默取消后续全部调度」的经典坑。一次随机定相位、固定周期使相位差永久保持。
- **全量兜底**：`DumpAllTask` 每 6h（`DumpService:83` `DUMP_ALL_INTERVAL_IN_MINUTE = 6*60`，`:197`），只防「事件永久丢失导致的漂移」，不是常规对账。**没有节点间 MD5 对账机制**。
- **端到端推论（m1/m2 问题）**：业务实例 m1 挂 A、m2 挂 B，配置写在 A——m2 走的是与 m1 **完全同构**的通知链：A 门铃催 B → B 自己 dump（查共享 DB）→ B 的 `ConfigCacheService.dump` 尾部发 B 进程内的 `LocalDataChangeEvent` → B 通知腿推 m2 → m2 回拉读 **B 磁盘**。通知源永远是「本节点 dump 完成」这一事件，节点不区分自己是写节点还是被催节点；**监听关系不出网**。

## 5. 读路径：内存摘要 + 磁盘正文 + 责任链

- **查询侧是责任链**，不是散在的 if-else：`ConfigChainEntryHandler → GrayRuleMatchHandler → SpecialTagNotFoundHandler → ConfigContentTypeHandler → FormalHandler(兜底)`——灰度/标签在链上被截获，未命中才走 formal。
- **内存里没有正文**：`ConfigCache` 只有三个 volatile 字段 `md5 / encryptedDataKey / lastModifiedTs`（+ `CacheItem` 的 groupKey/type/灰度 map）。`FormalHandler:50-52`：元数据出 `CacheItem`，正文出 `ConfigDiskServiceFactory.getInstance().getContent(...)`。
- **「磁盘」是抽象**：`ConfigDiskServiceFactory:41` 按 `-Dconfig_disk_type` 二选一——`rawdisk`（纯文件，默认）或 `rocksdb`（嵌入式 KV）。磁盘层的角色是「落盘的正文存储」，文件还是 KV 引擎不影响接口。
- **分层的本质是按体量与按需访问**：摘要（`md5 / lastModifiedTs`）小且恒需（变更判定、随读返回），常驻内存；正文大且只在命中时才给，变更重拉与冷启动才从磁盘读。磁盘「够用」还有个前提：**客户端自己缓存了全量**，服务端磁盘只承担变更后的重拉。

## 6. 变更感知：信号 + 回拉

- **变更推送**：客户端 listen 注册后，`RpcConfigChangeNotifier` 监听 `LocalDataChangeEvent` 推 `ConfigChangeNotifyRequest`——只带配置的定位信息、不带正文，客户端收到后 `getConfig` 回拉。
- **同一纪律贯穿全链**：从 DB 到磁盘到内存到通知到客户端，**服务端任何一跳都不推正文，正文永远由客户端拉**——与「内存不存内容」是同一条原则的两端（前者不占内存，后者不过网络）。
- `LocalDataChangeEvent` 另有消费者 `ConfigFuzzyWatchChangeNotifier`（3.0 模糊订阅）。

## 7. 客户端侧：缓存、快照、回调看门狗

- **`CacheData`**：每个 (dataId, group, tenant) 一份，JVM 内全量缓存 + md5；**磁盘快照**由 `LocalConfigInfoProcessor` 维护——读优先级 failover 文件 > 服务端 > 快照。两侧磁盘容灾对称：服务端磁盘兜 DB，客户端快照兜服务端。
- **两层 md5，各司其职**：第一层是**内容指纹**——服务端 `ConfigCache.md5` 与客户端 `CacheData.md5`（`:137` volatile，每次收到新内容用 `getMd5String(content)` 现算，`:202/:599`）互为镜像，回答「内容变了没」；第二层是 **listener 消费位点**——`ManagerListenerWrap.lastCallMd5`（`:633`），`checkListenerMd5()`（`:345-351`）遍历该配置的全部 listener，位点不等于当前 md5 的逐个回调，回答「这个 listener 收到过这版没有」。一份配置多个 listener，各推各的位点、互不阻塞。
- **at-least-once + 搭车重试**：位点推进（`lastCallMd5 = md5`，`:482`）写在 `receiveConfigInfo()` **之后、try 之内**——回调抛异常只记日志、位点不前进，下一次 `checkListenerMd5()`（变更回拉时驱动，`ClientWorker:1083`）会再调它，直到成功才停。与下面的看门狗构成闭环：卡死的 listener 被「反复重试 + 反复报警」双重暴露。
- **新 listener 不回放历史**：注册时 `lastCallMd5` 直接初始化为当前 md5（`:646`）——起点即现状，不吃旧值。同一个 32 位字符串身兼**版本号 / 消费位点 / 注册起点**三职，与命名面 `AbstractClient.revision` 的对账同族；差别在 config 的位点簿记放进了客户端的 wrap——订阅状态连服务端都不出。
- **回调看门狗（`CacheData:466-471`，可移植模式）**：调用户 `receiveConfigInfo` **前** `schedule` 一个 `LongNotifyHandler`（默认 60s，`:59`），**构造时捕获 `Thread.currentThread()`**；finally `cancel(true)`。触发即取卡住线程**实时栈**（前 5 帧）打 WARN 并发布 `ChangeNotifyBlockEvent`。四个要点：①Java 不能强杀用户线程，对「跑在自家通知线程上的不可信回调」唯一正确姿势是把静默卡死变成带栈的可诊断事件；②正常路径只付 schedule+cancel；③监控池单线程 + `DiscardPolicy`（`:90-92`）——观测组件永不反压业务；④warn 语义非 kill。

## 8. 三档写通道：一致性档位按部署形态选

| 形态 | 写通道 | JRaft | 集群同步 |
|---|---|---|---|
| 单机 derby | `StandaloneDatabaseOperateImpl` 直写 | 不经 | 无 |
| 集群内嵌 derby | `DistributedDatabaseOperateImpl`：`protocol.write/writeAsync` 多数派提交后应用到本地 derby（`:431/:475`），`DerbySnapshotOperation` 做 raft 快照（`:502`） | **config 数据面专属** | raft 复制 + 事件链**被 gate 关闭**（§2） |
| 生产外置 DB | `ExternalConfigInfoPersistServiceImpl` 直写 | 不经 | 门铃 + 轮询 + 全量（§4） |

口径精确化：「JRaft 只用于开发测试」对 **config 数据面**成立，但 JRaft 在**元数据 CP 面**（`SERVICE_METADATA`、持久实例、鉴权/命名空间，生产也用）常驻。一句话：**配置写面的一致性档位是选出来的——内嵌选共识，生产选共享权威，单机什么都不选**。

---

*关联：[harbor-vs-nacos.md](harbor-vs-nacos.md)（注册中心字帖，§8 附 nacos-client 命名契约）*
