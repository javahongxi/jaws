# 流量调度 / Command

Command 是分组级的跨组流量调度能力，支持将多个 group 的服务按权重合并，以及基于 IP 的路由规则。与配置热更新（接口级）不同，Command 的数据在整个 group 内共享，按接口名 pattern 匹配生效。

## 配置方式

### Nacos 模式

在 Nacos 控制台 → 配置管理 → 配置列表中：

- **Data ID**：`jaws.command`
- **Group**：`JAWS_` + 服务分组大写（默认 `JAWS_DEFAULT_RPC`）
- **配置格式**：JSON
- **配置内容**：command JSON（格式见下方示例）

### ZooKeeper 模式

在 `/jaws/{group}/command` 节点写入 JSON 内容。

## 配置示例

```json
{
  "clientCommands": [
    {
      "pattern": "org.hongxi.jaws.sample.api.*",
      "mergeGroups": ["default_rpc:80", "gray_rpc:20"],
      "routeRules": ["192.168.1.* to 10.0.0.*"]
    }
  ]
}
```

## 字段说明

| 字段 | 说明 |
|------|------|
| `pattern` | 接口名匹配模式，支持通配符 `*` |
| `mergeGroups` | 要合并的分组及权重，格式 `group:weight`（weight 为 0-100 整数） |
| `routeRules` | IP 路由规则，格式 `fromIP to toIP`，支持 `*` 通配和 `!` 取反 |

## 工作原理

Consumer 订阅 command 后，根据自身引用的接口名匹配 pattern，命中后按 mergeGroups 订阅多个 group 的服务列表并按权重路由，同时可按 routeRules 过滤目标 IP。
