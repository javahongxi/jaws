# MCP 支持 / AI 工具调用

Jaws 服务方法自动注册为 MCP（Model Context Protocol）工具，经 Stateless Streamable HTTP（`POST /mcp`）供 AI Agent 发现与调用。能力内置于 jaws-core 的 HTTP/1.1 传输层，Provider 无需额外代码与依赖。

## 架构与协议选型

实现位于 `org.hongxi.jaws.transport.http.mcp`，共三个类：

| 类 | 职责 |
|----|------|
| `McpHandler` | `POST /mcp` 协议处理：Accept 校验、JSON-RPC 解析、方法分发 |
| `McpToolRegistry` | 工具注册表：Provider 接口方法自动注册、参数解析与类型转换 |
| `McpMessageCodec` | JSON-RPC 2.0 编解码，基于 fastjson2 自包含，不依赖 MCP Java SDK |

协议采用 **Stateless Streamable HTTP**（MCP spec 2026-07-28，即 Spring AI 所称 MODERN 协议）：无 initialize 握手、无 session、无 GET 监听流、无 DELETE，每个 POST 请求独立处理。无状态意味着任意实例都能应答任意请求，天然适配负载均衡与水平扩缩容，也与 Jaws 服务端"请求-响应"的执行模型同形。

支持的方法：

| 方法 | 行为 |
|------|------|
| `tools/list` | 返回全部工具的 name/description/inputSchema，附缓存提示 `ttlMs=60000`、`cacheScope=public`（工具按进程静态注册，可安全缓存） |
| `tools/call` | 按 name 查找工具并执行，结果包装为 `{content: [{type: "text", text: ...}]}`；业务异常返回 `isError: true` 而非 JSON-RPC error |
| `ping` | 返回空结果，用于连通性探测 |

其余方法返回 `-32601 Method not found`。请求 Accept 头须包含 `application/json` 或 `text/event-stream`，否则 400；响应一律为 `application/json`（无 SSE 流）。工具执行派发到 serverExecutor 业务线程池，不阻塞 Netty IO 线程；线程池打满时返回 503。

## 工具自动注册

服务导出时（`JawsExporter`），若传输层为 `http` 或 `adaptive`，接口的每个 public 方法自动注册为一个 MCP 工具：

- **name**：方法名；**description**：`接口简名.方法名`；
- **inputSchema**：按参数类型生成 JSON Schema（string/integer/number/boolean/array/object），全部参数标记 required；
- **参数绑定**：按参数名从 `arguments` 匹配（依赖根 pom 已开启的 `-parameters` 编译参数），数值/布尔/String 直接转换，复杂类型经 JSON 反序列化为目标类型；
- **异步返回**：`CompletableFuture` 返回值会 join 后再序列化；
- **重载方法**：MCP 工具仅按名字标识，同名重载跳过并告警；default/synthetic 方法不注册。

## 使用方式

**Provider 端**：使用 HTTP/1.1 传输即可，`POST /mcp` 与 `POST /invoke`、REST 路由同端口共存：

```java
protocolConfig.setTransportFactory("http"); // 或 "adaptive"
```

curl 直接验证：

```bash
# 列出工具
curl -X POST http://localhost:10000/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","method":"tools/list","id":1,"params":{}}'

# 调用工具
curl -X POST http://localhost:10000/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","method":"tools/call","id":2,"params":{"name":"hello","arguments":{"name":"lily"}}}'
```

**Consumer 端（AI Agent）**：任何标准 MCP 客户端均可接入。以 Spring AI MCP Client 为例（完整示例见 `jaws-sample-mcp-consumer`）：

```yaml
spring:
  ai:
    mcp:
      client:
        streamable-http:
          connections:
            jaws:
              url: http://localhost:10000
              protocol: MODERN   # 关键：对齐 stateless 协议，缺省会走 legacy 握手而失败
```

发现的工具经 `ToolCallbackProvider` 暴露，可直接挂给 ChatClient 供 LLM 调用。注意 Spring AI 默认给工具名加 `<connection>_` 前缀（如 `jaws_hello`）。

运行示例：先启动 `jaws-sample-http-provider`（HttpProvider，端口 10000），再启动 `jaws-sample-mcp-consumer`，后者启动即完成工具发现与 `hello`/`getUsers` 两次 tools/call 验证。

## 能力边界

- 仅实现 tools 能力，不含 resources/prompts/sampling，也不提供订阅推送（客户端 listen 应保持关闭）；
- 工具执行直接反射调用实现类，**不经过 RPC Filter 链**（TokenAuth、AccessLog 等对 MCP 调用不生效），生产暴露需结合网络层访问控制或网关鉴权；
- 重载方法不支持；inputSchema 对复杂类型仅标记 `object`，不展开嵌套字段。