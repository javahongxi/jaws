# MCP (Model Context Protocol) 传输

> **实现规范版本**: `2025-03-26` (Streamable HTTP, stateful)
>
> MCP 规范演进：
> - `2024-11-05` — 初始 HTTP+SSE 传输（已废弃）
> - `2025-03-26` — Streamable HTTP（有状态，含 initialize 握手）← **当前实现**
> - `2025-11-25` — Streamable HTTP 修订版
> - `2026-07-28` — Streamable HTTP（无状态，移除会话和 GET 流）

Jaws 原生支持 MCP Streamable HTTP 传输，将 RPC 服务接口自动暴露为 AI Agent 可调用的 MCP Tools。无需额外 SDK，Cursor、Claude Desktop、MCP Inspector 等 MCP 客户端可直接连接。

## 架构原理

### Protocol SPI 集成

MCP 作为 Jaws Protocol SPI 的一等公民实现，扩展名为 `mcp`：

```
ServiceConfig.doExport()
    → Protocol("mcp") → McpProtocol
        → McpExporter
            → McpServer (Netty HTTP/1.1)
            → 自动注册接口方法为 MCP Tools
    → Registry.register() (注册到 Zookeeper/Nacos)
```

用户只需配置 `protocol=mcp`，即可享受完整的导出、注册、优雅停机链路。

### 核心组件

| 组件 | 职责 |
|---|---|
| `McpProtocol` | `@Extension("mcp")`，server-only Protocol 实现 |
| `McpExporter` | 桥接层：创建 Server、自动注册 Tools、委托生命周期 |
| `McpServer` | Netty HTTP/1.1 Server，Pipeline: HttpServerCodec → Aggregator → McpHandler |
| `McpHandler` | 路由 POST/GET/DELETE `/mcp` 端点，处理 JSON-RPC 2.0 消息 |
| `McpSessionManager` | 管理 MCP 会话生命周期（initialize → initialized → 正常调用） |
| `McpToolRegistry` | 工具注册表，存储 name/description/inputSchema/executor |
| `McpMessageCodec` | JSON-RPC 2.0 消息编解码（Request/Notification/Response） |
| `SseEncoder` | SSE 帧编码（`id: ...\nevent: message\ndata: {...}\n\n`） |

### 自动工具注册

`McpExporter` 在构造时扫描 Provider 的接口方法，自动注册为 MCP Tools：

1. **JSON Schema 生成** — 从方法参数类型映射：`String` → `"string"`，`int/long` → `"integer"`，`double/float` → `"number"`，`boolean` → `"boolean"`，`List/array` → `"array"`，其他 → `"object"`
2. **参数名保留** — 依赖 `-parameters` 编译参数，通过 `Parameter.getName()` 获取真实参数名
3. **反射调用** — Tool executor 通过 `Method.invoke()` 调用实际实现，支持 `CompletableFuture` 异步返回值
4. **类型转换** — MCP 传入的 `Map<String, Object>` 参数按方法签名自动转换（复用 HTTP transport 的 `convertValue` 模式）
5. **重载处理** — 同名重载方法自动跳过（MCP tool 按 name 路由，不支持重载），日志输出警告

### 端口共享

多个服务导出到同一端口时，`McpExporter` 通过 `ConcurrentMap<String, McpServer> SERVER_MAP` 按 `host:port` 共享 Server 实例（与 `JawsExporter` 共享 `ProviderMessageHandler` 的模式一致）。第一个 Exporter 创建并绑定端口，后续 Exporter 复用同一个 Server 并注册各自的 Tools。

### 端点路由

单一端点 `/mcp`，按 HTTP Method 分发：

| Method | 用途 | 响应 |
|---|---|---|
| `POST` | 接收 JSON-RPC 2.0 消息 | initialize → JSON；Notification → 202；Request → SSE 流 |
| `GET` | 建立 SSE 监听流（服务端推送） | `text/event-stream` 长连接 |
| `DELETE` | 终止会话 | 200 OK |

### 会话生命周期

```
Client                              Server
  |                                    |
  |--- POST initialize (无 session) -->|  创建 session，返回 Mcp-Session-Id
  |<-- JSON result + Mcp-Session-Id --|
  |                                    |
  |--- POST notifications/initialized >|  标记 session 为 fully initialized
  |<-- 202 Accepted ------------------|
  |                                    |
  |--- POST tools/list (带 session) -->|  返回已注册的 tools 列表
  |<-- SSE stream with JSON result ---|
  |                                    |
  |--- POST tools/call (带 session) -->|  调用实际 Java 方法
  |<-- SSE stream with tool result ---|
  |                                    |
  |--- DELETE (带 session) ----------->|  销毁 session
  |<-- 200 OK ------------------------|
```

## 使用方式

### 配置

```java
ProtocolConfig mcpProtocol = new ProtocolConfig();
mcpProtocol.setName("mcp");
mcpProtocol.setPort(10000);

ServiceConfig<DemoService> serviceConfig = new ServiceConfig<>();
serviceConfig.setInterface(DemoService.class);
serviceConfig.setRef(new DemoServiceImpl());
serviceConfig.setProtocol(mcpProtocol);
serviceConfig.export();
```

接口方法自动变为 MCP Tools，无需手动注册或注解。

### 编译要求

`pom.xml` 需启用 `-parameters` 编译参数（保留方法参数名）：

```xml
<compilerArgs>
    <arg>-parameters</arg>
</compilerArgs>
```

### 与 MCP Inspector 连接

```bash
npx @modelcontextprotocol/inspector
# URL: http://localhost:10000/mcp
```

## curl 测试脚本

### 1. 初始化握手

```bash
curl -s -i -X POST http://localhost:10000/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","method":"initialize","id":1,"params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"curl","version":"1.0"}}}'
```

从响应头提取 `Mcp-Session-Id`，后续请求需携带此 header。

### 2. 发送 initialized 通知

```bash
SESSION_ID="<上一步返回的session-id>"

curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:10000/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "Mcp-Session-Id: $SESSION_ID" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'
```

期望返回 `202`。

### 3. 列出可用工具

```bash
curl -s -X POST http://localhost:10000/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "Mcp-Session-Id: $SESSION_ID" \
  -d '{"jsonrpc":"2.0","method":"tools/list","id":2,"params":{}}'
```

### 4. 调用工具

```bash
# 调用 hello(name)
curl -s -X POST http://localhost:10000/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "Mcp-Session-Id: $SESSION_ID" \
  -d '{"jsonrpc":"2.0","method":"tools/call","id":3,"params":{"name":"hello","arguments":{"name":"Jaws"}}}'
```

期望响应（SSE 格式）：

```
event: message
data: {"id":3,"jsonrpc":"2.0","result":{"content":[{"type":"text","text":"Hello, Jaws"}]}}
```

```bash
# 调用 getUsers()
curl -s -X POST http://localhost:10000/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "Mcp-Session-Id: $SESSION_ID" \
  -d '{"jsonrpc":"2.0","method":"tools/call","id":4,"params":{"name":"getUsers","arguments":{}}}'
```

```bash
# 调用 countOrders()
curl -s -X POST http://localhost:10000/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "Mcp-Session-Id: $SESSION_ID" \
  -d '{"jsonrpc":"2.0","method":"tools/call","id":5,"params":{"name":"countOrders","arguments":{}}}'
```

### 5. 终止会话

```bash
curl -s -o /dev/null -w "%{http_code}" -X DELETE http://localhost:10000/mcp \
  -H "Mcp-Session-Id: $SESSION_ID"
```

期望返回 `200`。此后再发送请求将返回 `400`（session 已销毁）。

### 一键测试脚本

```bash
#!/bin/bash
BASE=http://localhost:10000/mcp
CT="Content-Type: application/json"
ACC="Accept: application/json, text/event-stream"

echo "=== 1. Initialize ==="
RESP=$(curl -s -D - -X POST $BASE -H "$CT" -H "$ACC" \
  -d '{"jsonrpc":"2.0","method":"initialize","id":1,"params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"curl","version":"1.0"}}}')
SID=$(echo "$RESP" | grep -i "Mcp-Session-Id" | tr -d '\r' | awk '{print $2}')
echo "Session-Id: $SID"
echo "$RESP" | tail -1 | python3 -m json.tool

echo -e "\n=== 2. Initialized ==="
curl -s -o /dev/null -w "HTTP %{http_code}\n" -X POST $BASE -H "$CT" -H "$ACC" \
  -H "Mcp-Session-Id: $SID" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'

echo -e "\n=== 3. Tools/List ==="
curl -s -X POST $BASE -H "$CT" -H "$ACC" -H "Mcp-Session-Id: $SID" \
  -d '{"jsonrpc":"2.0","method":"tools/list","id":2,"params":{}}'
echo

echo -e "\n=== 4. Call hello ==="
curl -s -X POST $BASE -H "$CT" -H "$ACC" -H "Mcp-Session-Id: $SID" \
  -d '{"jsonrpc":"2.0","method":"tools/call","id":3,"params":{"name":"hello","arguments":{"name":"Jaws"}}}'
echo

echo -e "\n=== 5. Call countOrders ==="
curl -s -X POST $BASE -H "$CT" -H "$ACC" -H "Mcp-Session-Id: $SID" \
  -d '{"jsonrpc":"2.0","method":"tools/call","id":4,"params":{"name":"countOrders","arguments":{}}}'
echo

echo -e "\n=== 6. Delete Session ==="
curl -s -o /dev/null -w "HTTP %{http_code}\n" -X DELETE $BASE -H "Mcp-Session-Id: $SID"
```

## 示例

参考 `jaws-sample-mcp-provider` 模块，启动后 DemoService 和 OrderService 的方法自动暴露为 MCP Tools。
