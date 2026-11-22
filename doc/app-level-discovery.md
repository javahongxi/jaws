# 应用级服务发现（local 元数据模式）

Jaws 以最小切片实现了应用级服务发现，对齐 Dubbo 3 默认的 `metadata-type=local` 模式：注册中心里每个应用只有一条轻量实例（path=应用名，参数携带 revision 指针），接口列表等重数据存 provider 进程内存，consumer 按 revision 通过现有 jaws 协议 RPC 拉取；接口→应用映射写注册中心的 DynamicConfiguration。

这是一个**可选注册模式**，不是替换：`registerMode=interface`（默认）的接口级主路径完全不变，两条路径可在同一集群并存。与 Dubbo 的逐项对照和边界清单见下文。

## 存储模型

```
                 ┌──────────────────────────────────────────────┐
                 │              Registry（三腿同形）              │
                 │                                              │
   naming 面     │  jaws/sample-app-provider                    │
  （实例指针）    │    └─ 10.0.0.1:20020  revision=a1b2c3...      │   ← 每应用一条，
                 │                                              │     同 ip:port 覆盖更新
   config 面     │  mapping:org.hongxi...DemoService             │
  （接口→应用）   │    └─ "sample-app-provider"                   │   ← group JAWS_CONFIG
                 └──────────────────────────────────────────────┘
                        ▲ register/discover          ▲ getMapping/setMapping
                        │                            │
        ┌───────────────┴──────────┐      ┌──────────┴───────────────┐
        │        Provider          │      │        Consumer          │
        │                          │      │                          │
        │ ApplicationMetadataManager│◄─────│ AppLevelRegistryDirectory │
        │  revision → MetadataInfo │ RPC  │  按 revision 分组展开      │
        │  （内存 + LRU 100 历史）   │ 拉取  │  MetadataCache（LRU 100） │
        │                          │      │                          │
        │ MetadataService（内部服务）│      │  展开后的接口级 URL 交给    │
        │  同业务端口导出，不注册     │      │  RegistryDirectory 复用    │
        └──────────────────────────┘      └──────────────────────────┘
```

| 数据 | 存储位置 | 形态 | 生命周期 |
|---|---|---|---|
| 应用实例（ip/port + revision 指针） | registry naming 面 | 一条 path=应用名 的 URL | 随注册中心自身机制（临时实例掉线即删） |
| 应用元数据 MetadataInfo（serviceKey → 全量服务 URL） | provider 进程内存 | `ApplicationMetadataManager`，current + LRU 100 历史 | 进程死亡即消失，无持久化 |
| 接口→应用映射 | registry config 面（DynamicConfiguration） | key=`mapping:{接口名}`，value=应用名 | 显式删除才消失（`removeMapping`，unexport 不自动清理） |
| 元数据缓存（revision → MetadataInfo） | consumer 进程内存 | `MetadataCache`，静态 LRU 100 + 内容自校验 | 进程重启即冷启动，首次通知重新拉取 |

config 面不是另一个组件，而是**同一个注册中心的配置通道**，三腿落点各异：**nacos 腿**经独立 `ConfigService` 写入，key→dataId（`mapping:{接口名}`）、group=`JAWS_CONFIG`，落 Nacos `config_info` 表（DB 持久化）；**harbor 腿**走 Raft CP 路径，数据存内存 `ConfigStorage` + 本地日志/快照，无外部 DB；**zk 腿**是 `/jaws/dynamic-config/{key}` 下的持久 znode。三者都是写入即长期存在，不与 naming 临时实例的生命周期联动——这正是映射残留的来源（见边界清单）。

revision 是**内容寻址**：`MetadataInfo.calRevision()` 对排序后的服务 URL 内容取 MD5。同服务列表的所有实例算出同一 revision，consumer 侧同 revision 的 N 个实例只拉一次元数据——这是应用级发现瘦身的核心。

registry 三腿（zookeeper/nacos/harbor）**零改动**：它们本来就是 URL 泛化存储（如 HarborRegistry 把任意 URL 的 protocol/path 存进 instance metadata），path=应用名 的 URL 天然就是一条应用级实例。

一句话闭环：**注册中心里始终只有指针和映射，没有元数据本体**——provider 写 naming（实例 + revision）与 config（mapping），consumer 先查 config 拿应用名、订阅 naming 拿实例、按 revision 向 provider 内存拉元数据并展开成接口级地址。

## 网络与存储收益

应用级发现的收益不止于对齐 k8s「一个应用一个 Service」的模型，核心在数据模型本身：

| 维度 | 接口级 | 应用级 |
|---|---|---|
| 注册中心条目数 | O(接口数 × 实例数)：100 接口 × 1000 实例 = 10 万条 | O(实例数)：同规模仅 1000 条 |
| 单条体积 | 全量 URL（methods/参数/group/version 等），KB 级 | ip:port + revision 指针 |
| consumer 订阅数 | 每引用一个接口一条订阅/监听 | 每应用一条订阅 |
| 元数据同步 | 随每条 URL 注册/推送 | revision 去重，同 revision 的 N 个实例只拉一次 |

条目数与单条体积的双降，直接缩小注册中心内存、快照体积与集群同步流量（Raft/Distro），注册、重连 redo、变更推送的 payload 也随之缩小。revision 内容寻址是瘦身的核心：同服务列表的 N 个实例共享同一 revision，consumer 侧 `MetadataCache` 进程级共享，第二个接口引用同应用直接命中、零 RPC；且拉取发生在地址刷新路径而非调用路径——稳态下（服务列表不变）除实例心跳外没有任何额外流量。

一个 trade-off：变更粒度从接口粗化到应用——任何一个服务变更都会 bump revision、通知该应用的**所有** consumer（接口级只通知订阅了该接口的 consumer）。但推送的是轻量指针、元数据拉取又被 revision 去重，总流量仍远低于接口级；代价主要是 consumer 多做一次「展开后发现与本接口无关」的计算。

## 使用方式

**Provider 端唯一的开关**是 `RegistryConfig.registerMode`：

```java
RegistryConfig registryConfig = new RegistryConfig();
registryConfig.setProtocol("nacos");
registryConfig.setAddress("127.0.0.1");
registryConfig.setPort(8848);
registryConfig.setRegisterMode("instance");   // 默认 "interface"，走原接口级路径
```

**Consumer 端没有开关**：引用配置完全普通，`ConsumerCoordinator` 查 mapping 键决定路由——键存在即走应用级目录，否则走原接口级目录。因此 provider 切到 instance 模式后，存量 consumer 无需任何改动。

一键示例（应用级 provider/consumer 对，nacos 腿）：

```bash
# 前置：本地 Nacos 已在 127.0.0.1:8848 运行
./run-sample.sh app                 # AppProvider -> AppConsumer 一次性演示
```

观察日志：

- provider：`Registered application instance: application=..., revision=..., interface=...`——每次导出重算 revision 并覆盖注册同一条实例
- consumer：`Fetched metadata from {ip}:{port}, ..., revision=...`——真 RPC 拉取；`Metadata cache hit: ..., revision=...`——共享缓存短路（第二个接口引用同应用时命中）

### mapping 清理：`ServiceNameMapping.removeMapping`

mapping 是持久配置项，unexport 不自动删（与 Dubbo 一致）。但「配置存在即路由」是隐式开关：残留 mapping 会把后续的接口级消费静默改道到已下线的应用（订阅为空、refer size=0）。因此拥有生命周期的调用方应在优雅停机时显式清理：

```java
// 示例 provider 的 main 末尾注册：优先级 12——业务 unexport（10）之后、
// registry close（默认 20）之前，此时 config 客户端仍存活
ShutdownHook.registerShutdownHook(() -> {
    ServiceNameMapping.removeMapping(DemoService.class.getName());
    ServiceNameMapping.removeMapping(OrderService.class.getName());
}, 12);
```

`removeMapping` 委托 `DynamicConfiguration.removeConfig`，三腿同形，返回是否真的删掉了条目。app/app2/app3 三个示例均已内置该钩子：一键冒烟用普通 `kill`（SIGTERM）停 provider 后 mapping 即归零，紧跑接口级示例不再被改道（nacos/zk/harbor 三腿均经冒烟实证）。`kill -9` 不触发钩子，仍需手动清理（nacos admin API 删 config、zkCli 删对应 znode、harbor `POST /api/config?key=mapping:{接口名}&remove=true`）。

## Provider 导出/卸载链路

`ServiceConfig.doExport` 对 instance 模式的 registry（按 registry 逐个判定，支持与 interface 模式的 registry 混配）：

1. `ApplicationMetadataManager.addServiceUrl(app, serviceUrl)` 重算 revision，旧 revision 移入 LRU 历史（滚动发布期间持旧快照的 consumer 仍能拉到）
2. 首次导出时以同一 `ProtocolConfig` 内部导出 `MetadataService`：registries 为空即不注册，ref 是查 manager 的 delegation，继承业务的 group/version/token（服务端 dispatch 按 attachments 里的 group/interface/version 定位 provider）；按应用 guard 防重复导出
3. 注册应用实例 URL：`serviceUrl.createCopy()` 后 `setPath(application)`、加 `revision` 参数；同 ip:port 重复注册即覆盖更新（Harbor/Nacos 均支持）
4. `ServiceNameMapping.setMapping(接口名, app)` 写 DynamicConfiguration

多协议 + instance 模式：**fail-fast 抛异常**（v1 边界，一条实例指针无法表达多协议端点）。

`unexport` Phase 1：`removeServiceUrl` → 应用还有其他服务则按新 revision 重注册实例（先 unregister 旧 URL 再 register 新 URL，兼容按全参数去重的实现），服务清空则 deregister 实例；**mapping 不删**（与 Dubbo 一致，显式删除入口 `removeMapping` 见使用方式）。内部 MetadataService 随自身 ShutdownHook 关闭，hook 优先级 15——排在业务 unexport（10）之后、registry close（20）之前，避免提前 stopAccept 共享端口破坏业务 drain。

## Consumer 发现链路

`ConsumerCoordinator.forRegistry`：先对 registryUrls 调 `RegistryFactory.getRegistry`（确保 DynamicConfiguration 已初始化），再 `ServiceNameMapping.getMapping(refUrl.getPath())`；命中 → `AppLevelRegistryDirectory`，未命中 → 原 `RegistryDirectory`。泛化调用的 path 是真实接口名，天然兼容。

`AppLevelRegistryDirectory extends RegistryDirectory`：

1. **订阅**：以 `consumerUrl.createCopy().setPath(appName)` 订阅 + 立即 discover（与父类相同的异步通知竞态防护）
2. **分组展开**：通知到达的 urls 是应用实例——按 `revision` 参数分组（无 revision 的实例跳过并告警）；每组解析元数据后展开 `services` 中匹配本接口（`URL.canServe`：path/protocol/group/version）的 URL，跨 revision 按 identity 去重，调 `super.notify` 完成 refer/去重/回收/空保护——父类逻辑零改动
3. **元数据解析短路**：`MetadataCache.get(revision)` 命中即零 RPC；miss 则组内随机选实例（`Collections.shuffle`，避免热点），用实例 host/port/protocol 构建一次性 `MetadataService` 直连 Reference（复用业务端口与传输参数，请求携带实例的 group/version attachments），调 `getMetadataInfo(revision)` 后立即 `destroy()`；返回值做双重 revision 校验（stamp 一致 + 重算一致）后写缓存
4. **失败兜底**：组内实例逐个尝试；全失败则**本次通知作废**（当前地址不动），单线程 ScheduledExecutor 每 10s 重放最新实例快照直至成功（AtomicBoolean 防重入，destroy 时取消）——对齐 Dubbo `AddressRefreshRetryTask` 的最小形态
5. **销毁**：退订应用级 URL、关 retry 任务、关 registry（不复用父类按 consumerUrl 退订的逻辑）

缓存是 consumer 进程级静态 LRU（100 条），get/put 均重算 revision 与 key 比对自校验（对齐 Dubbo `AbstractCacheManager.validate`），坏条目即时驱逐。同应用多接口引用共享同一份缓存：第一个接口拉取后，其余接口全部命中。

## 与 Dubbo local 模式逐项对照

| 维度 | Dubbo 3.3 local 模式 | Jaws 最小切片 |
|---|---|---|
| revision 计算 | 服务列表内容哈希 | 同（排序后内容 MD5，顺序无关） |
| 实例存储 | naming 临时实例 + revision 等少量 metadata | 同（一条 path=应用名 的 URL） |
| 元数据存储 | provider 内存 `MetadataServiceDelegation` | 同（`ApplicationMetadataManager`，含 LRU 100 历史） |
| 拉取触发 | 地址刷新路径（实例变更通知），非调用路径 | 同（notify 时分组解析） |
| 短路层级 | 三级：① 组内实例已挂 MetadataInfo → ② MetaCacheManager → ③ RPC | 两级：① MetadataCache → ② RPC（省略实例挂元数据一级，实例 URL 保持纯指针） |
| RPC 形态 | 组内随机实例、一次性代理、复用业务端口、调后即 destroy | 同（现有 jaws 协议直连 Reference；无 V2 protobuf stub） |
| 拉取重试 | 外层最多 3 次、间隔 1s | 组内实例逐个尝试（无外层定时重试，失败进 10s 地址重试） |
| 缓存 | LRU 100 + 本地文件（启动加载、60 分钟落盘）+ 内容自校验 | LRU 100 + 内容自校验（**无文件缓存**） |
| 失败兜底 | 全失败通知作废 + 10s 重试；部分失败先通知成功部分 | 全失败通知作废 + 10s 重试；**任一 revision 失败整体作废**（更保守） |
| 接口→应用映射 | config 面 group=mapping，CAS 写入，监听热更新 | config 面 key=`mapping:{接口}`，**后写覆盖无 CAS，refer 时读一次不监听** |
| 映射清理 | 不自动删（残留无害） | 同；另提供 `removeMapping` 显式删除，示例停机钩子清理 |
| 双注册迁移 | register-mode=all 双注册过渡 | **不支持**（按 registry 混配两种模式替代） |
| remote 元数据中心 | MetadataReport 抽象（Nacos config/ZK 等） | **不支持**（上报时机对照见下节） |

## remote 模式元数据上报时机（Dubbo 对照，Jaws 未实现）

Jaws v1 只有 local 模式：元数据在 provider 内存，不存在「上报」动作。本节描述 Dubbo remote 模式（`MetadataReport`）的行为，作为后续扩展的参照——nacos 腿的 `NacosMetadataReport` 全程只持有 `ConfigService`，以 dataId=应用名、group=revision 写配置项，落 `config_info` 表 DB 持久化。

上报触发点（事件驱动为主）：

| 触发 | 说明 |
|---|---|
| 服务 export / unexport | 与实例注册在**同一条导出链路**上：重算 revision → 注册实例 → `publishAppMetadata` 写 config；revision 未变则跳过，进程内 `allMetadataReports` 缓存去重，同 revision 不重复写 |
| consumer refer | `storeConsumerMetadata` 上报订阅侧配置（运维用途） |
| 失败重试 | 写失败进 `failedReports`，按 `retry-period`（默认 3000ms）重试，最多 `retry-times`（默认 100）次 |
| 每日全量刷新 | `cycle-report` 默认 **true**：首次执行在凌晨 2~6 点间随机，之后每 24h `publishAll()` 重刷内存缓存的全部元数据；这是唯一的周期性上报，可配 `cycle-report=false` 关闭 |

默认异步写（`sync-report=false`），不阻塞导出主链路；另有一份接口粒度的 `ServiceDefinition`（methods/types，供网关/服务测试用，`report-definition` 控制）走同一通道。

与 instance 信息的频率对比：

- **触发事件基本相同**：两者都挂在 export/revision 变更链路上，都是事件驱动、不是周期全量推送
- **元数据写得更少**：revision 内容寻址去重——同应用同服务列表的 N 个实例写的是同一个配置项（幂等覆盖），进程内同 revision 只报一次；实例注册则是每实例各一条
- **但元数据多一个每日全量刷新**（cycle-report），实例侧没有对应物
- **实例的「高频」部分是心跳，不携带数据**：Nacos 2.x/3.x 客户端每 5s 发一次 `HealthCheckRequest`（gRPC 应用层心跳），只保活、不重传实例内容；实例全量数据只在注册/重连 redo 时传。不能拿「5s 一次心跳」对比元数据上报频率——稳态下两者都不传业务数据

附带结论：remote 模式下元数据**下线不删**——`unReportMetadata` 的唯一调用点被 fixme 注释（同 revision 可能被其他实例共享、无引用计数），Nacos `config_info` 无 TTL 也不与 naming 联动，旧 revision 配置长期累积成孤儿数据，正确性无害但需运维侧清理。后续若扩展 remote 模式（如以 Harbor config 面承担元数据中心），同样要面对这个无引用计数的清理问题。

## 边界清单（v1 明确不做）

- **remote 元数据模式**：元数据只在 provider 内存，进程死亡即消失；无 MetadataReport/元数据中心抽象
- **双注册迁移**：无 `register-mode=all`；过渡靠不同 registry 配不同模式混跑
- **mapping CAS 与热更新**：同接口多应用提供时后写覆盖（value 为单应用名）；mapping 仅在 refer 初始化时读一次，运行中改写不生效
- **多协议应用**：instance 模式 + 多协议 fail-fast 抛异常
- **文件缓存**：consumer 重启后缓存冷启动，首次通知重新 RPC 拉取
- **mapping 自动清理**：unexport 不删 mapping（与 Dubbo 一致）。残留对应用级消费自身正确性无害——consumer 只按在线实例携带的 revision 取数；但会把后续接口级引用静默改道到已下线应用（订阅为空，走父类空保护）。框架提供 `ServiceNameMapping.removeMapping` 显式删除入口，三个 app 示例以优先级 12 的停机钩子清理（见使用方式）；`kill -9` 不触发钩子
- **同 JVM 多应用共享端口**：内部 MetadataService 按应用导出，两个应用共用同一端口时服务 identity 冲突（导出校验抛异常）；一应用一进程或一应用一端口不受影响

## 代码地图

| 类 | 位置 | 职责 |
|---|---|---|
| `MetadataInfo` | `jaws-core` `metadata` | 元数据 POJO + 内容寻址 revision |
| `MetadataService` | 同上 | 内部拉取接口 `getMetadataInfo(revision)` |
| `ApplicationMetadataManager` | 同上 | provider 内存存储，current + LRU 历史 |
| `MetadataCache` | 同上 | consumer 静态 LRU 缓存 + 自校验 |
| `ServiceNameMapping` | 同上 | 接口→应用映射读/写/删（DynamicConfiguration） |
| `RegistryConfig.registerMode` | `config` | provider 侧唯一开关 |
| `ServiceConfig` | `config` | instance 模式导出/卸载接线 + 内部 MetadataService 导出 |
| `AppLevelRegistryDirectory` | `cluster/directory` | 订阅应用实例、分组展开、拉取与重试 |
| `ConsumerCoordinator.forRegistry` | `cluster` | mapping 驱动的路由分发 |
| `jaws-sample-app-provider/consumer` | `jaws-samples` | 一键示例（`./run-sample.sh app`） |