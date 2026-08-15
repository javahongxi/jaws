# Nacos 与 ZooKeeper 注册中心实现对比

本文档对 Jaws 框架中 Nacos 和 ZooKeeper 两种注册中心实现进行全方位比较。

## 一、底层存储模型

| 维度 | Nacos | ZooKeeper |
|------|-------|-----------|
| **数据模型** | 扁平化服务模型（serviceName + group + instances） | 树形层级路径模型（类似文件系统） |
| **路径结构** | `jaws/{path}` 作为 serviceName，group 来自 URL | `/jaws/{group}/{path}/server/{host:port}` 多层路径 |
| **数据存储** | URL 参数全部存入 Instance 的 `metadata` Map | URL 完整字符串作为 ZK 节点的 data（byte[]） |
| **节点类型** | 无区分，instance 只有 ephemeral 属性 | 区分 `AVAILABLE_SERVER` 和 `CLIENT` 两种节点类型 |

**ZK 路径层级**（见 `ZkUtils`）：

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

**核心区别**：ZK 依赖临时节点 + 会话心跳实现自动下线；Nacos 依赖心跳 + 健康检查机制。

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

## 四、断线重连机制

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

`NacosRegistry` **没有**连接状态监听。Nacos 客户端 SDK 内部处理了心跳和重连，`NamingService` 会自动维护注册状态。

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
| **超时** | `CONFIG_LONG_POLL_TIMEOUT` | `sessionTimeoutMs` + `connectionTimeoutMs` 分离 |

## 九、总结

| 特性 | Nacos | ZooKeeper |
|------|-------|-----------|
| **数据模型** | 扁平，面向服务注册设计 | 通用协调服务 |
| **健康检查** | 服务端主动心跳检测 | 会话超时 + 临时节点自动消失 |
| **变更通知** | 推送全量实例列表 | Watch 通知 + 客户端重新拉取 |
| **重连恢复** | SDK 内部处理，应用层无感 | 应用层监听 `RECONNECTED` 手动恢复 |
| **消费者感知** | 不记录消费者信息 | 创建 CLIENT 临时节点标记消费者 |
| **配置中心** | 原生 ConfigService 支持 | 用节点 data 模拟，需自建路径规范 |
| **代码复杂度** | 较低（~183行），API 更高级 | 较高（~267行），需手动管理节点生命周期 |

简而言之：**Nacos 实现更简洁**，因为 Nacos SDK 封装了更多服务注册的高层语义；**ZooKeeper 实现更底层**，需要手动处理节点创建/删除、会话重连、消费者标记等细节，但控制粒度也更细。
