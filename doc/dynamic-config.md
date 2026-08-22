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

| 配置项          | 全局 Key                  | 说明                               |
|-----------------|---------------------------|------------------------------------|
| 请求超时        | `jaws.requestTimeout`     | 毫秒，方法级 > 服务级 > 全局       |
| 重试次数        | `jaws.retries`            | Failover 策略重试次数              |
| 最大工作线程    | `jaws.maxWorkerThreads`   | Provider 过载保护线程数上限        |
| Filter 开关     | `jaws.filter.<name>.enabled` | 按名称启用/禁用 Filter          |
| 路由规则        | `jaws.route.rule`         | 动态路由规则（JSON）               |
| 路由权重        | `jaws.route.weight.<svc>` | 按服务动态调整权重                 |

## 配置中心

默认使用内存配置（`LocalDynamicConfiguration`）。接入远程配置中心后自动切换：

- **Nacos** — 引入 `jaws-registry-nacos` 模块，自动通过 Nacos ConfigService 管理配置
- **ZooKeeper** — 引入 `jaws-registry-zookeeper` 模块，自动通过 CuratorFramework 管理配置

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
