# HTTP/2 传输与裸 TCP 传输对比

jaws 采用单端口多传输自适应（裸 TCP / HTTP/2 / HTTP/1.1），同一套协议可跑在不同传输之上。本文以 **裸 TCP 与 HTTP/2** 两种传输的对比为主线（帧结构、请求-响应关联、固有开销），并单列 **HTTP/1.1 的明文分帧特征**，顺带说明 HTTP/2 的分帧语义、HPACK 头压缩，以及 gRPC 线格式的来源。

> 性能数字（QPS/延迟）一律以 [`benchmark.md`](benchmark.md) 的实测为准；本文只描述结构与机制差异，不给出未经实测的百分比。wire 协议与 gRPC 的互通细节见 [`wire-grpc-compat.md`](wire-grpc-compat.md)。

## 一、两个正交轴：协议 × 传输

jaws 有两个彼此独立的维度，不要混为一谈：

- **协议（帧的语义）**：jaws 协议（自定义二进制头，以 Java 接口为契约）/ wire 协议（gRPC 线格式，以 IDL 为契约）。
- **传输（字节怎么搬）**：裸 TCP / HTTP/2 / HTTP/1.1，由 adaptive 层在单端口上探测分流。

本文主角是 **jaws 协议 × {裸 TCP, HTTP/2}**；gRPC（wire）作为参照系。

## 二、帧结构：一次一元调用发了几帧

| 传输 | 请求 | 响应（一元成功） | 收尾方式 |
|---|---|---|---|
| **裸 TCP** | 1 帧（自定义头 + body） | 1 帧 | 单帧即完整消息 |
| **jaws over HTTP/2** | HEADERS + DATA(`endStream=true`) | HEADERS + DATA(`endStream=true`) | DATA 带 endStream，**无 trailers** |
| **gRPC（wire）** | HEADERS + DATA(5 字节 prefix + 消息) | HEADERS + DATA + **HEADERS(trailers)** | 结尾独立 trailers 帧 |

一次一元往返的帧数：裸 TCP = 1+1；jaws-over-h2 = 2+2；gRPC = 2+3。

```
裸 TCP:      [ 自定义头 | body ]                      ← 请求 1 帧
jaws over h2: [HEADERS] [DATA(endStream)]             ← 请求 2 帧；HEADERS 带 x-jaws-* 镜像元数据 + 固定 :path，body 是权威 payload
gRPC:         [HEADERS] [DATA(5B prefix + protobuf)]  ← 请求 2 帧
              响应额外多一个 [HEADERS(trailers: grpc-status)]
```

> **gRPC 的 5 字节前缀是 per-message 的 Length-Prefixed-Message，不是 per-frame**：它是 DATA 帧 payload 开头的 `1 字节压缩标志 + 4 字节消息长度`，紧跟在 9 字节帧头之后，且 9 字节帧头里的 `Length` 字段把这 5 字节连同消息一起计入。一条消息大于单帧（受 max frame size / 流控窗口限制）会**跨多个 DATA 帧**，前缀只出现在首帧；多条小消息也能**挤进同一个 DATA 帧**，各自带一个前缀。所以"每个 DATA 帧多 5 字节"不准确，应是"每条消息多 5 字节、落在承载它的首个 DATA 帧 payload 开头"。

jaws-over-h2 的帧证据：客户端 `Http2Client` 发 `DefaultHttp2HeadersFrame` + `DefaultHttp2DataFrame(payload, endStream=true)`；服务端 `Http2StreamServerHandler` 同样 `write(HEADERS)` 后 `writeAndFlush(DATA, endStream=true)`。**用 endStream 的 DATA 收尾，省掉了 gRPC 强制的 trailers 帧。**

**路由信息是"两头都有"**：payload body 才是权威（`Http2PayloadCodec.encodeRequest` 写 `interfaceName / methodName / paramDesc / requestId / retries / args / attachments`）；同时把 interface/method/param-desc/group/version 以 `x-jaws-*` 头**镜像**进 HEADERS（`Http2Constants` 注释：*"mirrored from payload for gateway visibility"*），让 L7 网关不解析 body 就能路由/观测。`:path` 固定为 `/jaws/rpc`（不像 gRPC 的 `/pkg.Svc/Method` 每方法变化），响应状态走 HTTP `:status`（200/400/500/503）。

### HEADERS 字段并排对照（jaws-over-h2 vs gRPC）

| HEADERS 字段 | jaws-over-h2 | gRPC（wire） |
|---|---|---|
| `:method` | POST | POST |
| `:scheme` | http / https | http / https |
| `:authority` | host:port | host:port |
| `:path` | **固定 `/jaws/rpc`**（路由在 payload，镜像头辅助） | **`/package.Service/Method`**（每方法变化，网关靠它路由） |
| `content-type` | `application/jaws` | `application/grpc(+proto / grpc-web)` |
| 序列化 | `x-jaws-serialization`（hessian2/fastjson2/…） | 隐含 protobuf；`grpc-encoding` 只管消息压缩 |
| 接口 / 方法 / 参数签名 | `x-jaws-interface` / `x-jaws-method` / `x-jaws-param-desc`（**从 payload 镜像**，供网关可见） | 编码在 `:path` 里，无独立镜像头 |
| 分组 / 版本 | `x-jaws-group` / `x-jaws-version`（可选） | 无对应（走自定义 metadata） |
| 流式模式 | `x-jaws-streaming`（unary/server/bidi/client） | 由调用类型决定，无专门头 |
| 超时 | 框架层处理（不在头里） | `grpc-timeout` |
| 压缩协商 | — | `grpc-encoding` / `grpc-accept-encoding` |
| trailers 预告 | **无**（用 endStream 收尾） | `te: trailers` |
| 状态 | HTTP `:status`（200/400/500/503）+ 结果/异常在 body | HTTP `:status` + **`grpc-status`/`grpc-message` 在结尾 trailers 帧** |

**读法**：jaws-over-h2 的 HEADERS 其实**不薄**——一组 `x-jaws-*` 镜像头 + content-type/serialization/streaming。它相对 gRPC 真正省的是：`:path` 固定（不随方法变）、无 `te:trailers`、**无结尾 trailers 帧**、无 5 字节消息前缀。而 gRPC 把路由信息塞进 `:path`，jaws 改用**独立镜像头**表达，目的是让网关既能路由、又不必解析 payload。

> 字段来源：请求头 `Http2Client.buildRequestHeaders`（Http2Client.java:532-571）、常量 `Http2Constants`、响应 `:status` 见 `Http2StreamServerHandler`、body 权威字段见 `Http2PayloadCodec.encodeRequest`。

### HTTP/1.1 的明文分帧特征

jaws 的 HTTP/1.1 分支由 `HttpServerCodec` + `HttpObjectAggregator` 承载，落到 `HttpRequestHandler`（消费 `FullHttpRequest`）：`POST /invoke` 把 JSON body 派发给 RPC，另有注解式 REST 路由与 `GET /health`。

**"明文"体现在分帧（头部编码），不在 body**：

```
POST /invoke HTTP/1.1\r\n
Host: 127.0.0.1:20880\r\n
Content-Type: application/json\r\n
Content-Length: 57\r\n
\r\n
{"interface":"…","method":"…","args":[…]}
```

- 请求行 + 头部是 **ASCII 行式文本**，`\r\n` 分隔、空行 `\r\n\r\n` 结束——三种传输里唯一"头部能肉眼直读"的。对比：HTTP/2 是**二进制帧**（HEADERS 经 HPACK、DATA 带 9 字节二进制帧头），裸 TCP 是**自定义二进制头**。
- **body 是不是明文取决于内容类型**（这里恰好是 JSON 文本，但 HTTP/1.1 同样能传二进制），与协议无关——**HTTP/2 也能带 JSON body**。所以"body 是 UTF-8 文本"**不能用来区分 HTTP/1.1 与 HTTP/2**，真正的差异在头部/分帧。
- **"一次性写 socket"不是协议保证**：`Content-Length` 定长时头+体通常一次 flush；但 `Transfer-Encoding: chunked` 会把 body 切成 `<hexlen>\r\n<data>\r\n` 多段分次写，keep-alive/pipelining 还会在同连接串多条报文。"明文"说的是头部编码，不是"整条一次写"。
- 到 `HttpRequestHandler` 这一层，头部**已被 `HttpServerCodec` 解析成 `FullHttpRequest`**，看到的是解析后的结果；明文分帧的证据在上游 codec，不在 handler。

## 三、请求-响应如何关联

- **裸 TCP**：payload 头里带 `requestId(long)`，客户端维护 `requestId → ResponseFuture` 表做关联；requestId 是唯一的 demux 键。
- **HTTP/2**：**两层各管一段**——
  - 传输层：每个 RPC 用 `Http2StreamChannelBootstrap` 开一条 HTTP/2 stream，Netty 的 frame codec 按 **stream-id** 自动把响应帧路由回对应 stream channel；
  - 协议层：payload 里**仍带 `requestId`**（`Http2PayloadCodec` 读写），客户端回调用它匹配。

为什么 h2 上 requestId 看似冗余仍要保留：requestId 属于**协议层**，jaws 的 request/response 模型要跨 TCP 与 HTTP/2 复用同一套 codec 与 callback 表；stream-id 属于**传输层**。这正是"协议 × 传输"解耦带来的约束与好处。

## 四、HTTP/2 为什么把 HEADERS 与 DATA 分成两帧

不是因为"方便中间设备识别"，根因是它要继承 HTTP 语义并承载流模型：

1. **HTTP 本就是头/体分离**：HTTP/2 是 HTTP/1 的多路复用传输层，请求头/响应头 + body 的模型是 HTTP 自带的，h2 只是编码成两种帧。
2. **HEADERS 是开流标志**：收到一个流的 HEADERS 帧，这个 stream 才算建立；DATA 帧依附于已存在的流。
3. **头与体编码方式不同**：HEADERS 走 HPACK 压缩（头可字典化），DATA 是原始字节（是否压缩由应用决定）。分帧才能分别用不同编码。
4. **扩展性**：分开才能承载 PUSH_PROMISE、1xx 中间响应、以及 gRPC 的 trailers。

**中间设备能路由只是副产品**：路由所需的 `:method/:path/:scheme/:authority` 放在 HEADERS 里，L7 网关不必解析 body 就能转发（Envoy 读 `:path` 即知调用哪个 gRPC 方法）。而 h2 的二进制分帧，初衷恰恰是**减少中间设备对明文的魔改**，更偏端到端。

## 五、HPACK 是什么

HPACK（RFC 7541）是 HTTP/2 的头部压缩算法，由三个机制组成：

- **静态表**：预定义常见头名/值（如 `:method: GET`），发索引即代表整条头。
- **动态表**：**连接级** FIFO 缓存（默认 4KB），发过的头存表，后续同头只引用索引；发送端与接收端各维护一份**同步**的动态表——**这是有状态的**。
- **Huffman 编码**：未命中的新字符串用一套静态 Huffman 码表逐字段压缩。

代价：动态表两端必须状态一致（连接不可随意迁移、代理要重建表）；表不跨连接共享（新连接需预热）；存在 HPACK bomb（撑爆内存）与表复用侧信道风险。

对 jaws 的意义：jaws-over-h2 的 HEADERS 并不"空"——它带一组 `x-jaws-*` 镜像头 + 固定 `:path`，只是**没有 gRPC 的 `te:trailers`/`grpc-*` 那套、也不需要结尾 trailers 帧**，所以 HPACK 负担比 gRPC 轻，但仍有自定义头的编码成本；**裸 TCP 根本没有"头"，完全绕开 HPACK**。

## 六、HTTP/2 相对裸 TCP 的固有开销

jaws 走 HTTP/2 时，即便不碰 gRPC 语义，也要付 HTTP/2 框架层的成本，而裸 TCP 全无：

- **帧头开销**：每个 HTTP/2 帧 9 字节固定头（length 3 + type 1 + flags 1 + streamId 4）。
- **每流状态**：每条并发 stream 一个 `Http2StreamChannel` 状态对象，随并发增长。
- **流控**：连接级 + 流级 flow control，需要 `WINDOW_UPDATE` 帧与窗口维护。
- **二进制分帧层**：帧的编解码、交错与重组的 CPU 成本。

## 七、多路复用与 TCP 层队头阻塞

- 裸 TCP：一条长连接靠 requestId 并发复用多个在途请求。
- HTTP/2：一条连接上多 stream 复用，省连接数。

**结论**：HTTP/2 的多路复用只发生在**帧层**——所有 stream 的帧交织进同一条字节流。任何一个 TCP 段丢失，恢复期内该连接上**全部 stream** 的帧都滞留在内核接收缓冲区，无法交付应用。解决 TCP HOL 属于 QUIC / HTTP/3 的范畴。两种传输都跑在 TCP 上时，这一层风险相同；差别只在裸 TCP 与 h2 都把整条连接的交付进度绑在同一个编号轴上。

### 7.1 根源：有序交付是 TCP 的 API 契约，不是实现缺陷

TCP 用 32 位序列号给每个**字节**编号（不是给报文编号）。接收端的契约是：只向应用交付从上次读取位置开始**连续**的数据。

- 收到 0–999、2000–2999，缺 1000–1999 → 内核把 2000–2999 **缓存在 receive buffer**（不丢弃），向上只交付 0–999。
- 接收端持续发 **duplicate ACK**（确认号不变），告知发送端"仍在等 1000"。
- 发送端的两级恢复手段：
  - **快速恢复**（RACK-TLP，RFC 8985）：累计一个 RTT 的 dupACK 缺口即重传，不必等满 3 个 dupACK。
  - **尾包丢失**（其后再没有新段到达，凑不出 dupACK）：只能等 RTO（Linux 初始 200ms，指数退避）。

"缓存而非丢弃"这个动作本身就是队头阻塞的成因。若 TCP 把 2000–2999 先交给应用，对上层就是撕裂的字节流，无法解析。TCP 没有"这段字节属于哪个上层对象"的概念，它只有一个编号轴。

### 7.2 一次丢包的时间线

设 MSS = 1448 字节（1500 − 20 IP − 20 TCP − 12 timestamp 选项），CUBIC，RTT = 100ms，服务端在一条 h2 连接上并发推 4 个 stream：

| 字节范围 | 承载内容 | 状态 |
|---|---|---|
| 0–1447 | S1 响应第 1 段 | 已交付 |
| 1448–2895 | S2 响应 | **网络中丢失** |
| 2896–4343 | S3 响应 | 滞留内核 |
| 4344–5791 | S4 响应 | 滞留内核 |
| 5792–7239 | S1 响应续 | 滞留内核 |

S3、S4、S1 续帧在语法上完全独立、HPACK 索引可解、流控额度充足——应用层仍拿不到，因为它们排在一个缺失的字节区间之后。

阻塞时长（这是**交付延迟**，与拥塞窗口收缩是两条独立代价）：

- 快速恢复路径：约 **1 个 RTT**，上例即 4 个 stream 同时停 100ms；高 RTT 链路按 RTT 等比放大。
- RTO 路径：**至少 200ms**，退避后可到秒级。
- WiFi（802.11）与蜂窝：MAC 层链路级重传对 TCP 不可见，TCP 侧不判丢包，但交付同样停滞。

裸 TCP 上的形态一致：jaws 的 requestId 在途表同样依赖这条字节流的交付进度，一个段丢失会把该连接上全部在途请求的响应一起堵住。

### 7.3 与 HTTP/1.1 的关系（避免误读）

- **未开 pipelining 的 HTTP/1.1**：一条连接同一时刻只有一个在途响应，其字节全属于同一个对象，HOL 爆炸半径 = 1 个对象，本来就没有"别人的队头"可堵。
- **开启 pipelining 的 HTTP/1.1**：多个响应共享一条连接，**与 HTTP/2 在 TCP 层完全同构**，风险一模一样。pipelining 当年被放弃的原因（响应顺序 HOL、错误恢复语义歧义、中间代理实现错误率、慢速链路收益为负）**都不是 TCP HOL**。
- 所以"两种传输都跑在 TCP 上时这一层风险相同"成立；工程上的真实缓解是浏览器时代的**多 TCP 连接 + 域名分片**，属于绕过而非解决。

## 八、权衡与选型

- **裸 TCP**：帧最省、无 HTTP/2 框架层，适合私有 RPC 的内部高频调用。
- **HTTP/1.1**：头部 ASCII 行式、无二进制分帧/HPACK，最容易被通用工具（curl / 浏览器 / 任意 HTTP 代理）直读直写与调试；代价是无多路复用（一条连接同一时刻一问一答，靠连接池撑并发）、头部明文冗余大。jaws 用它做对外的 REST 入口。
- **HTTP/2**：换来生态互通、网关可路由、标准 HTTP 语义，代价是框架开销。
- **jaws-over-h2**：折中——用 HTTP/2 传输但**不套用 gRPC 语义**：`:path` 固定、无 5 字节消息前缀、用 endStream 的 DATA 收尾（不建 trailers）；同时把 interface/method 等镜像进 `x-jaws-*` 头，保留 L7 网关可路由 / 可观测。从而在保住 h2 互操作性的同时压低框架开销。

一句话：**jaws-over-h2 比 gRPC 轻，省在"无 5 字节消息前缀、无 trailers 帧、`:path` 固定"；比裸 TCP 重，贵在"背了 HTTP/2 的帧/流/流控框架层 + 一组镜像头"。** 具体差多少，以 `benchmark.md` 实测为准。

## 九、关联文档

- wire 协议与 gRPC 线格式互通：[`wire-grpc-compat.md`](wire-grpc-compat.md)
- 编解码与线上数据格式：[`codec-comparison.md`](codec-comparison.md)
- 单端口多传输自适应：[`architecture.md`](architecture.md)
- 性能实测：[`benchmark.md`](benchmark.md)
