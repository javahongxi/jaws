# REST 桥接

将 Jaws RPC 服务通过 REST API 对外暴露，传统 HTTP 客户端无需理解 MCP 协议即可直接调用后端 RPC 服务。

> **注意：** REST 桥接已合并至 `jaws-mcp` 模块，与 MCP 桥接共享同一模块和 Spring Boot Starter。

## 工作原理

```
HTTP 客户端  ──REST (JSON)──▶  jaws-mcp RestInvokeServlet  ──Jaws RPC──▶  Provider
```

- 轻量 Servlet 实现，无 Spring MVC 依赖
- Spring Boot 启动时自动扫描所有 `ServiceBean`，将每个公开方法注册为可调用端点
- 与 MCP 桥接共享 `jaws-core` 中的 `ServiceMethodSpec`、`ArgumentConverter`、`JsonSchemaGenerator`
- 参数通过 JSON Schema 自动推导，支持复杂对象、集合、嵌套类型

## 依赖

```xml
<dependency>
    <groupId>org.hongxi</groupId>
    <artifactId>jaws-mcp-spring-boot-starter</artifactId>
    <version>${jaws.version}</version>
</dependency>
```

该 starter 同时包含 MCP 和 REST 桥接能力，均基于 HTTP 协议。

## 配置

在 `application.yml` 中添加：

```yaml
jaws:
  rest:
    enabled: true              # 是否启用 REST 桥接（默认 true）
    endpoint: /rest            # REST HTTP 端点前缀（默认 /rest）
    include-services:          # 可选：仅暴露指定服务（空则暴露全部）
      - org.hongxi.jaws.sample.api.DemoService
    exclude-services:          # 可选：排除指定服务
      - org.hongxi.jaws.sample.api.OrderService
```

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `enabled` | boolean | `true` | 是否启用 REST 桥接 |
| `endpoint` | string | `/rest` | REST HTTP 端点前缀 |
| `include-services` | list | `[]` | 白名单，为空时暴露所有已导出服务 |
| `exclude-services` | list | `[]` | 黑名单，优先级高于白名单 |

## API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/rest/services` | 列出所有已注册服务及其方法 |
| GET | `/rest/services/{interfaceName}` | 查看指定服务的方法详情（含 JSON Schema） |
| POST | `/rest/invoke/{interfaceName}/{methodName}` | 调用指定方法，body 为 JSON 参数 |

## 示例

启动示例（需要 Nacos 在 `127.0.0.1:8848` 运行）：

```bash
./mvnw spring-boot:run -pl jaws-samples/jaws-sample-provider-rest
```

服务启动后，REST 端点可用：`http://localhost:8083/rest`

## curl 验证

### 1. 列出所有服务

```bash
curl -s http://localhost:8083/rest/services | python3 -m json.tool
```

响应示例：

```json
{
  "services": [
    {
      "interfaceName": "org.hongxi.jaws.sample.api.DemoService",
      "methodCount": 7,
      "methods": [
        {
          "actionName": "DemoService_hello",
          "methodName": "hello",
          "parameters": [{"name": "arg0", "type": "java.lang.String", "javaType": "java.lang.String"}],
          "inputSchema": {
            "type": "object",
            "properties": {"arg0": {"type": "string"}},
            "required": ["arg0"],
            "additionalProperties": false
          },
          "returnType": "java.lang.String"
        }
      ]
    }
  ],
  "totalServices": 1
}
```

### 2. 查看指定服务方法详情

```bash
curl -s http://localhost:8083/rest/services/org.hongxi.jaws.sample.api.DemoService | python3 -m json.tool
```

### 3. 调用方法 — 基础类型参数

```bash
curl -s -X POST http://localhost:8083/rest/invoke/org.hongxi.jaws.sample.api.DemoService/hello \
  -H "Content-Type: application/json" \
  -d '{"arg0": "Jaws REST"}'
```

响应：

```json
{
  "actionName": "DemoService_hello",
  "interfaceName": "org.hongxi.jaws.sample.api.DemoService",
  "methodName": "hello",
  "success": true,
  "data": "Hello, Jaws REST"
}
```

### 4. 调用方法 — 无参方法

```bash
curl -s -X POST http://localhost:8083/rest/invoke/org.hongxi.jaws.sample.api.DemoService/getUsers \
  -H "Content-Type: application/json" \
  -d '{}'
```

响应：

```json
{
  "actionName": "DemoService_getUsers",
  "interfaceName": "org.hongxi.jaws.sample.api.DemoService",
  "methodName": "getUsers",
  "success": true,
  "data": [{"name": "lily", "age": 24}, {"name": "lucy", "age": 25}]
}
```

### 5. 调用方法 — 复杂对象参数

```bash
curl -s -X POST http://localhost:8083/rest/invoke/org.hongxi.jaws.sample.api.DemoService/rename \
  -H "Content-Type: application/json" \
  -d '{"arg0": {"name": "lily", "age": 24}, "arg1": "lily-renamed"}'
```

### 6. 错误处理

调用不存在的服务：

```bash
curl -s -X POST http://localhost:8083/rest/invoke/org.hongxi.jaws.sample.api.NotExist/hello \
  -H "Content-Type: application/json" \
  -d '{"arg0": "test"}'
```

响应（HTTP 404）：

```json
{
  "success": false,
  "error": "Method not found: org.hongxi.jaws.sample.api.NotExist/hello"
}
```

## 与 MCP 桥接的对比

| 特性 | MCP 桥接 | REST 桥接 |
|------|---------|----------|
| 协议 | JSON-RPC 2.0 + SSE | 普通 HTTP + JSON |
| 会话管理 | 需要 initialize → tools/call | 无状态，直接调用 |
| 适用场景 | AI Agent / MCP 客户端 | 传统 HTTP 客户端 / 调试 |
| 依赖 | MCP Java SDK | 无额外依赖 |
| Starter | `jaws-mcp-spring-boot-starter` | 同左（已合并） |
| 共享组件 | `ServiceMethodSpec`、`ArgumentConverter`、`JsonSchemaGenerator` | 同左 |

两个桥接可以同时启用，分别在不同端口提供服务。REST 和 MCP 均已合并至 `jaws-mcp` 模块。
