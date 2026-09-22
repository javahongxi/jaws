# 动态配置 / Dynamic Configuration

支持全局 / 服务级 / 方法级三层配置热更新，无需重启即可生效。

## 配置层级

优先级从高到低：

| 层级     | Key 格式                                              | 示例                                                     |
|----------|-------------------------------------------------------|----------------------------------------------------------|
| 方法级   | `jaws.<feature>.<interfaceName>.<methodName>`         | `jaws.requestTimeout.com.example.DemoService.sayHello`   |
| 服务级   | `jaws.<feature>.<interfaceName>`                      | `jaws.retries.com.example.DemoService`                   |
| 全局     | `jaws.<feature>`                                      | `jaws.requestTimeout`                                    |

上层配置作为默认值，下层配置覆盖上层。

## 支持的配置项

| 配置项       | 全局 Key                     | 说明                         |
|--------------|------------------------------|------------------------------|
| 请求超时     | `jaws.requestTimeout`        | 毫秒，方法级 > 服务级 > 全局 |
| 重试次数     | `jaws.retries`               | Failover 策略重试次数        |
| 最大工作线程 | `jaws.maxWorkerThreads`      | Provider 过载保护线程数上限  |
| Filter 开关  | `jaws.filter.<name>.enabled` | 按名称启用/禁用 Filter       |
| 路由规则     | `jaws.route.rule`            | 动态路由规则（JSON）         |
| 路由权重     | `jaws.route.weight.<svc>`    | 按服务动态调整权重           |

## 配置中心

默认使用内存配置（`LocalDynamicConfiguration`）。接入远程配置中心后自动切换：

- **Nacos** — 引入 `jaws-registry-nacos` 模块，自动通过 Nacos ConfigService 管理配置
- **ZooKeeper** — 引入 `jaws-registry-zookeeper` 模块，自动通过 CuratorFramework 管理配置

远程配置中心采用本地缓存 + 监听变更模式，热路径零远程调用开销。

> **Harbor 不是配置中心**，但提供一条演示用的动态配置**广播**通道：见下文《Harbor 广播通道》。

### 多注册中心约定（约定优于配置）

配置多个注册中心时，约定**第一个**注册中心（按配置顺序）作为动态配置中心，
后续注册中心自动跳过初始化，避免配置中心被覆盖和连接泄漏：

```
DynamicConfiguration initialized with registry type: zookeeper
DynamicConfiguration already initialized, skip registry type: nacos
```

- 若第一个注册中心没有对应的动态配置实现（未引入相应模块），则按顺序顺延到下一个；
- Provider 与 Consumer 各自独立初始化，请保持两端注册中心配置顺序一致，
  以确保读写同一个配置中心。

## Harbor 广播通道（演示用）

Harbor 定位为**注册中心**，不提供配置存储、不是配置中心。它只做一件事：把一条动态配置
变更通过已有的 bi-stream **广播**给所有已连接的 `jaws` 客户端，各客户端就地写进本进程的
`DynamicConfiguration`（默认即 `LocalDynamicConfiguration`）并触发监听器——用于演示
「改配置不重启即生效」。

链路：

```
POST /api/config?key=..&value=..            # Harbor HTTP 管理端点（grpcPort + 10）
        │
HarborServer.broadcastConfigChange          # 推给本节点 native 客户端（按 clientVersion 过滤）
        │  bi-stream push: DynamicConfigChangeRequest        ＋ 经 Distro 转发给 peers
HarborClient                                  # 纯协议 SDK：只解析该帧并 emit 给 listener
        │  setDynamicConfigListener
HarborRegistryFactory (jaws-registry-harbor)  # 胶水层：落到 DynamicConfigurationUtils
```

**集群行为**：广播只需对任意一个节点调用一次——该节点推给本地客户端后，经
`ConfigBroadcastSyncRequest` 转发给 distro peers，每个 peer 推给挂在自己身上的客户端
且**不再回转**（防环）。与 nacos 的集群同步不同：nacos 的跨节点通知只带元数据（对端从
共享 DB 读正文），harbor 没有共享权威，转发本身就携带变更内容。转发属集群面流量，同样
受成员地址守卫约束。

与配置中心的关键区别，都是「演示用」这一取舍的直接结果：

- **无存储、无补投**：fire-and-forget。客户端必须**在广播发生时已连接**才收得到；重启后
  回落默认值，直到下一次广播。Harbor 不留任何配置状态，也就无法向迟到/重连的客户端回放。
- **只广播给自家客户端**：广播按 `clientVersion` 过滤，真 `nacos-client` 与 Distro 节点
  连接不会收到——`DynamicConfigChangeRequest` 是 jaws 私有类型，喂给 nacos-client 会被
  误读为命名流量并触发重连。
- **分层不破**：`HarborClient` 保持纯协议 SDK（源码守护 `HarborSourceGuardTest` 禁止
  `client/` 依赖 `configcenter`）；「收到广播 → 写入 `DynamicConfiguration`」的胶水放在
  `jaws-registry-harbor`，与 nacos/zookeeper 把配置绑定放在各自 registry 模块的做法一致。
- **删除用显式标志**：`remove=true`（帧里的 `deleted`），不用 null，契合 `setConfig(null)`
  已被拒绝的契约。

示例（改全局默认超时为 500ms、热生效；再撤销回落默认）：

```bash
curl -X POST 'http://<harbor-host>:<grpcPort+10>/api/config?key=jaws.requestTimeout&value=500'
curl -X POST 'http://<harbor-host>:<grpcPort+10>/api/config?key=jaws.requestTimeout&remove=true'
```

> **与「多注册中心约定」并存时注意**：若首个注册中心是 Nacos/ZooKeeper，进程用的就是它们的
> 配置中心，Harbor 广播会写进那个远程实现而非内存 `LocalDynamicConfiguration`；只有当 Harbor
> 是唯一（或首个且无 nacos/zk 配置腿）时，广播才落到内存配置。演示场景请只配 Harbor 一条注册中心。

## 示例

通过 Nacos 控制台或 API 推送配置：

```
# 全局默认超时 500ms
jaws.requestTimeout = 500

# 某个服务的超时 1000ms
jaws.requestTimeout.com.example.OrderService = 1000

# 某个方法的超时 2000ms
jaws.requestTimeout.com.example.OrderService.createOrder = 2000

# 禁用 Token 鉴权 Filter
jaws.filter.tokenAuth.enabled = false
```
