# MCP 桥接

将 Jaws RPC 服务自动暴露为 [MCP (Model Context Protocol)](https://modelcontextprotocol.io) Tools，使 AI Agent 可通过 MCP 协议直接调用后端 RPC 服务。

## 工作原理

```
AI Agent  ──MCP (HTTP+SSE)──▶  jaws-mcp Servlet  ──Jaws RPC──▶  Provider
```

- 基于 MCP Java SDK 2.0.0，使用 `HttpServletStreamableServerTransportProvider` 作为传输层
- Spring Boot 启动时自动扫描所有 `ServiceBean`，将每个公开方法注册为 MCP Tool
- Tool 命名规则：`InterfaceName_methodName`，重载方法追加数字后缀（如 `save_1`、`save_2`）
- 参数通过 JSON Schema 自动推导，支持复杂对象、集合、嵌套类型

## 依赖

```xml
<dependency>
    <groupId>org.hongxi</groupId>
    <artifactId>jaws-mcp-spring-boot-starter</artifactId>
    <version>${jaws.version}</version>
</dependency>
```

该 starter 会自动引入 `jaws-mcp` 核心模块和 MCP SDK 依赖。

## 配置

在 `application.yml` 中添加：

```yaml
jaws:
  mcp:
    enabled: true              # 是否启用 MCP 桥接（默认 true）
    server-name: jaws-mcp-demo # MCP 服务名称
    server-version: 1.0.0      # MCP 服务版本
    endpoint: /mcp             # MCP HTTP 端点路径（默认 /mcp）
    include-services:          # 可选：仅暴露指定服务（空则暴露全部）
      - org.hongxi.jaws.sample.api.DemoService
    exclude-services:          # 可选：排除指定服务
      - org.hongxi.jaws.sample.api.OrderService
```

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `enabled` | boolean | `true` | 是否启用 MCP 桥接 |
| `server-name` | string | `jaws-mcp-server` | 对外宣告的 MCP 服务名称 |
| `server-version` | string | `1.0.0` | 对外宣告的 MCP 服务版本 |
| `endpoint` | string | `/mcp` | MCP HTTP+SSE 端点路径 |
| `include-services` | list | `[]` | 白名单，为空时暴露所有已导出服务 |
| `exclude-services` | list | `[]` | 黑名单，优先级高于白名单 |

## 示例

启动示例（需要 Nacos 在 `127.0.0.1:8848` 运行）：

```bash
./mvnw spring-boot:run -pl jaws-samples/jaws-sample-mcp
```

服务启动后，MCP 端点可用：`http://localhost:8082/mcp`

## curl 验证

### 1. 初始化会话

```bash
curl -s -D - -X POST http://localhost:8082/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test-client","version":"1.0.0"}}}'
```

响应头中包含 `Mcp-Session-Id`，后续请求需携带此值：

```
HTTP/1.1 200
Mcp-Session-Id: 70a79432-eff7-4faa-89ac-5558a9ce9e14
Content-Type: application/json

{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-03-26","capabilities":{"logging":{},"tools":{"listChanged":true}},"serverInfo":{"name":"jaws-mcp-demo","version":"1.0.0"}}}
```

### 2. 发送 initialized 通知

```bash
SESSION_ID="<上一步获取的 Mcp-Session-Id>"

curl -s -X POST http://localhost:8082/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "Mcp-Session-Id: $SESSION_ID" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'
```

### 3. 列出所有 Tools

```bash
curl -s -X POST http://localhost:8082/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "Mcp-Session-Id: $SESSION_ID" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
```

响应示例（以 `DemoService` 为例）：

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "tools": [
      {
        "name": "DemoService_hello",
        "description": "DemoService.hello(String): String",
        "inputSchema": {
          "type": "object",
          "properties": {"arg1836019240": {"type": "string"}},
          "required": ["arg1836019240"],
          "additionalProperties": false
        }
      },
      {
        "name": "DemoService_getUsers",
        "description": "DemoService.getUsers(): List",
        "inputSchema": {"type": "object", "additionalProperties": false}
      }
    ]
  }
}
```

### 4. 调用 Tool

```bash
curl -s -X POST http://localhost:8082/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "Mcp-Session-Id: $SESSION_ID" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"DemoService_hello","arguments":{"arg1836019240":"Jaws MCP"}}}'
```

响应：

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "result": {
    "content": [{"type": "text", "text": "Hello, Jaws MCP"}],
    "isError": false
  }
}
```

### 一键脚本

将以上步骤合并为单条命令：

```bash
SESSION_ID=$(curl -s -D - -X POST http://localhost:8082/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test-client","version":"1.0.0"}}}' \
  | grep -i "mcp-session-id" | tr -d '\r' | awk '{print $2}') \
&& curl -s -X POST http://localhost:8082/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "Mcp-Session-Id: $SESSION_ID" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}' > /dev/null \
&& curl -s -X POST http://localhost:8082/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "Mcp-Session-Id: $SESSION_ID" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"DemoService_hello","arguments":{"arg1836019240":"Jaws MCP"}}}'
```

## 编程式使用

如果不使用 Spring Boot Starter，也可以通过 `JawsMcpServer` Builder 手动构建：

```java
JawsMcpServer mcpServer = JawsMcpServer.builder()
    .serverInfo("my-mcp-server", "1.0.0")
    .mcpEndpoint("/mcp")
    .addService(DemoService.class, provider)
    .build();

// 停止时
mcpServer.stop();
```

完整示例参考 `jaws-samples/jaws-sample-mcp`。
