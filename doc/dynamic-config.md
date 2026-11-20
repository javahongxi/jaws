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
- **Harbor** — 引入 `jaws-registry-harbor` 模块，自动通过 `HarborDynamicConfiguration` 管理配置（独立连接，与 Nacos 腿同形，见下文）

远程配置中心采用本地缓存 + 监听变更模式，热路径零远程调用开销。

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

## Harbor 配置中心（Raft + 内存）

Harbor 提供兼容 nacos-client config 协议的配置中心：配置写入与持久实例走**同一个自研
Raft group**（CP），数据存内存 + 本地日志/快照（`~/.harbor/data/raft-<port>`），无外部
数据库；每个节点 apply 同一条日志后各推各的本地监听者，**无需节点间中继**（旧的 AP
广播演示通道已移除）。

jaws 的扁平 key 与 Nacos 腿**同一映射**：key → 独立 dataId，group `JAWS_CONFIG`
（tenant 默认 public）——一条腿写的 key 另一条腿读得回。registry-harbor 腿的
`HarborDynamicConfiguration` 是 `DynamicConfiguration` SPI 实现（`@Extension("harbor")`，
由 `AbstractRegistryFactory` 按 protocol 自动加载），**config 与 naming 各用各的连接**：
它自建一条专用 `HarborClient`，不复用 registry 的 naming 连接，与 Nacos 腿
NamingService/ConfigService 分离的形状一致。

链路：

```
POST /api/config?key=..&value=..            # Harbor HTTP 管理端点（grpcPort + 10）
        │  直写单 key：dataId=key，group=JAWS_CONFIG
HarborServer.publishConfig → Raft CONFIG_PUBLISH   # follower 转发 leader，全节点 apply
        │  每节点 apply 后推 ConfigChangeNotifyRequest 给本地监听者
HarborClient（config 专用连接）              # 标准 nacos config 协议：listen/query/push
        │  per-key 监听
HarborDynamicConfiguration (jaws-registry-harbor)  # localCache + 框架 ConfigurationListener 分发
```

**集群行为**：写入只需对任意一个节点调用一次（HTTP 或 SDK）——写经 Raft 日志提交，
所有节点 apply 后收敛，并各自推送挂在自己身上的客户端。与旧广播演示的本质区别：配置
有了持久权威，**晚启动的进程能读到已发布的配置**；断线重连后以缓存 md5 回放 listen，
断线期间的变更不会丢。

兼容与边界：

- **nacos-client 直接可用**：`ConfigService` 的 publish/get/addListener/remove 全走标准
  协议（需 `nacos.server.grpc.port.offset=0`）；删除走 `ConfigRemoveRequest`，监听者
  收到 null content 即「配置已删除」。
- **分层不破**：`HarborClient` 保持纯协议 SDK（源码守护 `HarborSourceGuardTest` 禁止
  `client/` 依赖 `configcenter`）；「收到内容 → 更新缓存并分发框架监听」的胶水
  放在 `jaws-registry-harbor` 的 `HarborDynamicConfiguration`，与 nacos 腿的
  `NacosDynamicConfiguration` 逐段同形。
- **不做**：console、版本回滚、灰度发布、审计；`casMd5` 接收但忽略（last-write-wins）；
  fuzzy watch 仍明确拒绝。读语义为节点内存直读（最终一致），不做 linearizable read。

示例（改全局默认超时为 500ms、热生效；再撤销回落默认，curl 契约与旧版一致）：

```bash
curl -X POST 'http://<harbor-host>:<grpcPort+10>/api/config?key=jaws.requestTimeout&value=500'
curl -X POST 'http://<harbor-host>:<grpcPort+10>/api/config?key=jaws.requestTimeout&remove=true'
```

也可用 `GET /api/configs` 查看全部已存配置（dataId/group/tenant/md5/content），
验证落盘与集群收敛。

> **与「多注册中心约定」并存时注意**：配置中心只由**首个**注册中心初始化。若首个是
> Nacos/ZooKeeper，进程用的就是它们的配置中心，Harbor 的 `HarborDynamicConfiguration`
> 根本不会初始化（也就不会多开那条 config 连接）；只有 Harbor 排在首位时，热配置才走
> Harbor。演示场景请只配 Harbor 一条注册中心。

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
