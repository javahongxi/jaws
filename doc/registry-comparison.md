# Nacos 与 ZooKeeper 注册中心实现对比

本文档对 Jaws 框架中 Nacos 和 ZooKeeper 两种注册中心实现进行全方位比较。

## 一、底层存储模型

| 维度 | Nacos | ZooKeeper |
|------|-------|-----------|
| **数据模型** | 扁平化服务模型（serviceName + group + instances） | 树形层级路径模型（类似文件系统） |
| **路径结构** | `jaws/{path}` 作为 serviceName，group 来自 URL | `/jaws/{group}/{path}/server/{host:port}` 多层路径 |
| **数据存储** | URL 参数全部存入 Instance 的 `metadata` Map | URL 完整字符串作为 ZK 节点的 data（byte[]） |
| **节点类型** | 无区分，instance 只有 ephemeral 属性 | 区分 `AVAILABLE_SERVER` 和 `CLIENT` 两种节点类型 |

**ZK 路径层级**（见 `ZkPathUtils`）：

```
/jaws/{group}/{servicePath}/server/{host:port}   ← 服务提供者
/jaws/{group}/{servicePath}/client/{host:port}   ← 服务消费者
```

**Nacos 映射**（见 `NacosPathUtils`）：

```
serviceName = "jaws/{path}"
group       = url.getGroup()
instance metadata = {protocol, path, ...所有URL参数}
```

## 二、服务注册机制

### ZooKeeper

1. **先删后建**：注册前先 `removeNode` 清除可能残留的旧节点，再 `createNode`
2. **双节点**：父路径为 `PERSISTENT` 类型，服务实例节点为 `EPHEMERAL` 类型（会话断开自动删除）
3. **数据载体**：节点 data 存储 `url.toFullStr()` 完整 URL 字符串

### Nacos

1. **直接注册**：调用 `namingService.registerInstance()` 即可，无需先清理
2. **单实体**：只注册一个 `Instance` 对象，设置 `ephemeral=true, healthy=true`
3. **数据载体**：URL 参数全部放入 `instance.metadata`，额外存储 `protocol` 和 `path`

**核心区别**：v2 模型下两者其实都是**连接活性驱动**——ZK 靠临时节点 + 会话超时自动下线；Nacos 2.x 靠客户端与服务端之间的 **gRPC 连接**（连接断即临时实例消失），**已无 1.x 的 HTTP 心跳**（详见 [harbor-vs-nacos.md](harbor-vs-nacos.md) 附录 8.5）。

## 三、服务订阅与发现机制

### ZooKeeper — CuratorCache 监听

- 使用 `CuratorCache` 监听 `/server` 路径下的子节点变化
- 监听 `NODE_CREATED` / `NODE_DELETED` 事件
- 事件触发后重新 `getChildren` 获取全量子节点列表，逐个 `getData` 解析 URL
- 订阅时还会创建 `CLIENT` 临时节点（消费者标记）

### Nacos — EventListener 监听

- 使用 `namingService.subscribe()` 注册 `EventListener`
- 收到 `NamingEvent` 后直接从中获取 `List<Instance>` 全量实例列表
- 从 metadata 中还原 protocol/path 构建 URL
- 不创建额外的消费者节点

**核心区别**：ZK 是**被动通知**（只告诉你节点变了，需要自己去拉最新数据）；Nacos 是**主动推送**（直接给你最新实例列表）。

## 四、会话保活与断线重连

先分层澄清两件事，避免"失败重连"被读成"操作层重试"：

- **会话保活**：由**注册中心那条长连接的 transport 层**维持——ZK 客户端按约 `sessionTimeout/3` 周期发 ping（Curator `sessionTimeoutMs`，jaws 默认 60s），Nacos 3.x 靠 gRPC 长连接本身的活性（v2/v3 **无应用层 beat**；1.x 的 `BeatReactor` 只在遗留 `NamingHttpClientProxy` 路径存在）。jaws **都不**自己发心跳。
- **断线重连 + 重放登记**：保活失败 → 客户端自动重连 → 新连接建立后**必须重放注册/订阅**（旧 session/connection 关联的临时节点/临时实例已被服务端清掉）。ZK 由 `ZookeeperRegistry` 挂 Curator `ConnectionStateListener` 显式做；Nacos 由 nacos-client 内部 `NamingGrpcRedoService` 做，**jaws 侧不实现**。
- **别混淆**：jaws 基类 `FailbackRegistry.retry()` 是"**API 调用抛错后的操作层定时重试**"（默认每 30s，`registryRetryPeriod`），且只在 `check=false` 才生效——`check=true`（生产默认）时 doRegister/doSubscribe 抛异常直接 fail-fast 退出。这条路径与"连接层重连"正交，README 里那句"掉线重连后自动重建登记"专指前者（连接层）。

### ZooKeeper — 显式重连

`ZookeeperRegistry` 在构造时注册了 `ConnectionStateListener`：

```java
if (connectionState == ConnectionState.RECONNECTED) {
    reRegisterServices();    // 重新注册所有服务
    reSubscribeServices();   // 重新订阅所有服务
}
```

因为 ZK 临时节点在会话断开后会丢失，重连后必须手动恢复。

### Nacos — 无显式重连

`NacosRegistry` **没有**连接状态监听。Nacos 客户端 SDK 内部靠 **gRPC 连接活性 + 客户端 redo 重放**维护注册状态（v2 无 beat：断连只把 redo 数据标脏位，重连后由 `RedoScheduledTask` 重发注册/订阅）——详见 [harbor-vs-nacos.md](harbor-vs-nacos.md) 附录 8.6。

## 五、服务发现（doDiscover）

| 维度 | Nacos | ZooKeeper |
|------|-------|-----------|
| **API** | `namingService.getAllInstances(serviceName, group)` | `curator.getChildren().forPath(parentPath)` |
| **数据解析** | 从 metadata 直接构建 URL | 逐个子节点 `getData` 读取 byte[] 再反序列化 URL |
| **容错** | metadata 无 protocol 时用 refUrl 兜底 | 节点 data 解析失败时用节点名解析 host:port 兜底 |
| **路径不存在** | Nacos SDK 内部处理 | 需先 `checkExists` 再 `getChildren` |

## 六、并发控制

两者结构完全一致：

- `clientLock`（ReentrantLock）保护订阅/取消订阅操作
- `serverLock`（ReentrantLock）保护注册/注销操作
- `serviceListeners` 都是 `HashMap<URL, Map<NotifyListener, 具体监听器>>`

## 七、动态配置对比

| 维度 | NacosDynamicConfiguration | ZookeeperDynamicConfiguration |
|------|---------------------------|-------------------------------|
| **存储** | Nacos ConfigService；key→dataId，group 固定为 `JAWS_CONFIG` | ZK 节点，路径 `/jaws/dynamic-config/{key}` |
| **读写** | `getConfig` / `publishConfig` | `getData` / `setData` / `create` / `delete` |
| **监听** | Nacos `Listener` 回调 | `CuratorCache` 监听节点变化 |
| **连接复用** | 独立创建 `ConfigService`（与注册用 NamingService 不同实例） | 独立创建 `CuratorFramework`（与注册用客户端不同实例） |
| **删除配置** | `removeConfig` | `curator.delete()` |

## 八、Factory 创建对比

| 维度 | NacosRegistryFactory | ZookeeperRegistryFactory |
|------|----------------------|--------------------------|
| **客户端** | `NamingFactory.createNamingService(Properties)` | `CuratorFrameworkFactory.builder().build()` |
| **重试策略** | Nacos SDK 内部管理 | `ExponentialBackoffRetry(1000, 3)` 显式配置 |
| **认证** | username/password 放入 Properties | `digest` 模式 ACL 认证 |
| **超时** | 不在 NamingService 属性里设（gRPC 的 serverCheck/keepAlive 超时走 `GrpcClientConfig` + 系统属性 `nacos.remote.client.grpc.*`） | `sessionTimeoutMs` + `connectionTimeoutMs` 分离 |

## 九、总结

| 特性 | Nacos | ZooKeeper |
|------|-------|-----------|
| **数据模型** | 扁平，面向服务注册设计 | 通用协调服务 |
| **实例活性** | v2：服务端以 gRPC 连接活性判临时实例（1.x 才是 HTTP 心跳） | 会话超时 + 临时节点自动消失 |
| **变更通知** | 推送全量实例列表 | Watch 通知 + 客户端重新拉取 |
| **重连恢复** | SDK 内部处理，应用层无感 | 应用层监听 `RECONNECTED` 手动恢复 |
| **消费者感知** | 不记录消费者信息 | 创建 CLIENT 临时节点标记消费者 |
| **配置中心** | 原生 ConfigService 支持 | 用节点 data 模拟，需自建路径规范 |
| **代码复杂度** | 较低（`NacosRegistry` 约 185 行），API 更高级 | 较高（`ZookeeperRegistry` 约 308 行），需手动管理节点生命周期 |

简而言之：**Nacos 实现更简洁**，因为 Nacos SDK 封装了更多服务注册的高层语义；**ZooKeeper 实现更底层**，需要手动处理节点创建/删除、会话重连、消费者标记等细节，但控制粒度也更细。

### 选型取舍：规模与一致性

上面的活性/重连机制两腿其实**结构同构**（活性即会话/连接，断了靠 ephemeral 消失 + 重连重放），所以"选谁"不取决于保活方式，而取决于**写模型**与**推送模型**：

- **写成本**：ZK 是 CP，注册/注销/健康翻转每次变更都走 ZAB 共识、写穿 quorum——而注册中心是**高频写**场景，ZAB 为协调而非高频写而生。Nacos 临时实例走 AP（Distro），先落本机再异步复制，写很便宜，才撑得起大注册表。
- **变更通知的形态（根因在 §一 存储模型）**：Nacos 扁平地按 `serviceName → 实例集合` 在服务端整体持有，变更时把**最新全量实例列表**主动**推**给订阅者（§三，注意仍是全量快照、非 diff 增量，只是省掉"通知后再拉"）；ZK 树形、一个实例一个 znode，jaws 用 `CuratorCache` 常驻监听 `/server` 子树（`start()` 一次即可，无需每次事件重挂 watch），但 ZK 无 diff 推送，拿最新列表只能重新 `getChildren` + 逐个 `getData` **回捞全量**（§三）。消费者 × 服务数一大，这种"每次抖动都全量回捞"若不合并就会被放大——jaws 用去抖窗口合并（见下条）。
- **通知节奏：谁做去抖**：ZK 只负责"子节点变了通知你一次"，**去抖该由客户端补**。Nacos 服务端推送按 service 为 key 去抖合并（`DEFAULT_PUSH_TASK_DELAY=500ms`，窗口内多次变更 merge 成一次推）。工业参照 Dubbo 对同样的 ZK watch 默认加 **5s 去抖**（`delay-notification`，`RegistryNotifier` latest-wins / `ZookeeperRegistryNotifier` 最小间隔节流）。**jaws 也已补上这层**：`CuratorCache` 每个增删事件只做一次轻量的 `offer`，真正的 `getChildren`+`getData`+`notify` 推迟到 `NotifyDebouncer` 的 flush 里做，突发窗口内合并成一次回捞+通知（默认 `registryNotifyDelay=500ms`，`<=0` 退化为逐事件即时、等价旧行为）。相较 Dubbo 的 5s，jaws 取 500ms 与 Nacos 同档、偏低延迟。
- **一致性换扩展上限——ZK 的被低估优点**：CP 下节点下线经共识提交后才让 watch 生效，消费者几乎不会拿到"已死实例"的窗口；Nacos 的 AP 路径在收敛前可能短暂推到一个刚挂的实例。中小规模，这笔"用一致性换扩展上限"的交换很划算。

**取舍结论**：中小规模选 ZK 完全够用，且存活语义更严格；规模一大则 Nacos 的 AP 写 + 主动推全量快照 + namespace/权重/健康面板成套治理是硬需求。一个现实前提——**只在"本来就在运维 ZK"（Kafka/Hadoop/Dubbo 生态）时复用 ZK 当注册中心最划算**；若没有，单为注册去维护一套 ZK，不如直接上功能更全的 Nacos。jaws 两腿都实现，正是让你按"已有什么 + 要多强存活一致性 + 规模多大"来选，而不被框架绑定。
