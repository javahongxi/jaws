# 配置热更新

Consumer 端订阅注册中心的配置节点，运行时动态调整参数，无需重启服务。

## 支持动态切换的参数

| 参数 | 说明 | 示例值 |
|------|------|--------|
| `loadbalance` | 负载均衡策略 | `random`、`roundRobin`、`leastActive` |
| `haStrategy` | 容错策略 | `failover`、`failfast`、`failback` |
| `requestTimeout` | 调用超时（ms） | `5000` |
| `retries` | 重试次数 | `2` |

## 配置维度

配置热更新为**接口级**，每个接口可独立配置，互不影响。参考 Dubbo 的设计。

## Nacos 模式

在 Nacos 控制台 → 配置管理 → 配置列表 中新建配置：

- **Data ID**：接口全限定名，如 `org.hongxi.jaws.sample.api.DemoService`
- **Group**：`JAWS_` + 服务分组大写（即 `jaws.registry.group` 配置值，默认 `JAWS_DEFAULT_RPC`）
- **配置格式**：JSON
- **配置内容**：

```json
{
  "loadbalance": "random",
  "haStrategy": "failfast",
  "requestTimeout": 5000,
  "retries": 2
}
```

## ZooKeeper 模式

在 `/jaws/{group}/{接口全限定名}/config` 节点写入 JSON 内容，Consumer 通过 CuratorCache 自动感知变更。
