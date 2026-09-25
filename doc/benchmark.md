# 性能测试

`benchmark.md` 是 jaws 各「协议 × 传输 × 序列化」组合的 QPS 口径事实源。所有基准由 `run-sample.sh` 统一驱动，环境为 macOS aarch64、8 核、loopback 真实 TCP。门面 README 的「约 14 万」等钝表述以本文为准。

## 快速运行

```bash
# injvm 协议基准（框架纯开销）
./run-sample.sh bench-injvm

# jaws + Netty 网络传输基准（同进程，默认）
./run-sample.sh bench-jaws

# 切换传输层（http2）
TRANSPORT=http2 THREADS=20 DURATION=40 ./run-sample.sh bench-jaws

# Wire 协议基准（gRPC wire format over HTTP/2）
THREADS=20 DURATION=40 ./run-sample.sh bench-wire

# Wire 协议开启 gzip 压缩
COMPRESSION=gzip ./run-sample.sh bench-wire

# Wire 消费端直连（绕过 ReferenceConfig 代理，直接 ManagedChannel.unaryCall）
DISPATCH=direct ./run-sample.sh bench-wire

# grpc-java 客户端基准：SERVER=wire 打 jaws wire 服务端（互操作反验），
# SERVER=grpc 打纯 grpc-java 服务端（正统 gRPC 参照）
SERVER=wire THREADS=20 DURATION=40 ./run-sample.sh bench-grpc

# 自定义参数
THREADS=8 WARMUP=5 DURATION=20 ./run-sample.sh bench-jaws

# 切换序列化方式
SERIALIZATION=hessian2 ./run-sample.sh bench-jaws

# 模拟业务耗时（Provider 端每次调用 sleep 5ms）
SLEEP=5 ./run-sample.sh bench-jaws

# 分进程压测：两个终端分别执行 provider 与 consumer
ROLE=provider ./run-sample.sh bench-jaws
ROLE=consumer THREADS=20 ./run-sample.sh bench-jaws
```

## Benchmark 环境变量

| 变量 | 说明 | 默认值 | 适用范围 |
|------|------|--------|----------|
| `THREADS` | 并发线程数 | 4 | 全部 |
| `WARMUP` | 预热秒数 | 5 | 全部 |
| `DURATION` | 测量秒数 | 10 | 全部 |
| `PORT` | 协议端口（bench-jaws 10010；bench-wire / bench-grpc 50051） | 见左 | bench-jaws / bench-wire / bench-grpc |
| `TRANSPORT` | 传输层：`netty`（默认 TCP）或 `http2` | netty | 仅 bench-jaws |
| `SERIALIZATION` | 序列化方式（`fastjson2` / `hessian2` / `protostuff` / `fury`） | fastjson2 | 仅 bench-jaws |
| `COMPRESSION` | 压缩方式（`gzip` 或空） | 空（不压缩） | 仅 bench-wire |
| `DISPATCH` | 消费端形态：`pipeline`（经 ReferenceConfig 代理）或 `direct`（`ManagedChannel` 直调） | pipeline | 仅 bench-wire |
| `SLEEP` | Provider 端模拟业务耗时（毫秒） | 0 | bench-injvm / bench-jaws |
| `ROLE` | 运行角色：`all` 同进程；`provider` / `consumer` 分进程 | all | bench-jaws / bench-wire / bench-grpc |
| `SERVER` | bench-grpc 的服务端栈：`wire`（互操作）或 `grpc`（正统 gRPC 参照） | wire | 仅 bench-grpc |
| `HOST` | provider 地址，consumer 直连目标 | 127.0.0.1 | 分进程模式 |

### 分进程模式说明

`ROLE=all`（默认）时，provider 与 consumer 在同一 JVM 内，使用进程内 `local` 注册中心；`ROLE=provider` / `ROLE=consumer` 时，两端分属独立 JVM，consumer 通过 `direct` 直连对接：直接指向 provider 的 `HOST:PORT`，无需外部注册中心。bench-jaws、bench-wire 与 bench-grpc 均支持。

bench-jaws 用法：

1. 终端 A：`ROLE=provider PORT=10010 ./run-sample.sh bench-jaws`，等待输出 `Provider is ready`；
2. 终端 B：`ROLE=consumer PORT=10010 THREADS=20 ./run-sample.sh bench-jaws` 执行压测；
3. 压测结束后 Ctrl+C 停止 provider。

bench-wire 用法（provider 长驻后可多轮复用，服务端保持 JIT 全热）：

1. 终端 A：`ROLE=provider ./run-sample.sh bench-wire`；
2. 终端 B：`ROLE=consumer THREADS=20 ./run-sample.sh bench-wire` 执行压测。

分进程模式下两侧必须保持 `PORT`、group/version 一致（group/version 为固定值）；bench-jaws 另需 `SERIALIZATION` 一致，bench-wire 另需 `COMPRESSION` 一致。

## 参数选择建议

| 场景 | 线程数 | 测量秒数 | 说明 |
|------|--------|---------|------|
| 快速验证 | 4 | 10 | 默认值，确认功能正常 |
| 常规压测 | 8~16 | 10 | 观察中等并发下的表现 |
| 极限吞吐 | 32~64 | 20 | 探索 QPS 天花板 |
| 模拟业务（有 sleep） | 8~16 | 20~30 | 单次调用慢，需更多时间积累样本 |

> QPS = 总调用次数 / 测量秒数。并发线程数决定"同时有多少请求在飞"，线程越多 QPS 越高，直到达到系统瓶颈。

**口径约定：分进程（`ROLE=provider` 长驻 + `ROLE=consumer` 多轮压测）为权威口径。** 详见「jaws + netty 吞吐演进」中的口径结论一节。跨日窗口的两次测量不可直接归因差值，须同窗口复测；本文各节 QPS 均标注采集日期与配置。

## 当前性能口径（截至 2026-09-25）

同一硬件（macOS aarch64、8 核、loopback TCP）、同一负载（`hello(String)` 单字符串往返）、20 线程、DURATION=40s、各轮 0 错误（含返回值校验）。

| 配置（客户端 → 服务端） | 传输 / 序列化 | 消费端口径 | QPS | 采集日 | 演进出处 |
|------|------|------|------|------|------|
| jaws → jaws | netty TCP / fastjson2 | 分进程长驻 | 峰值 **142,876**，5 轮均值 **140,692** | 09-07 | jaws + netty 演进 |
| wire → wire | HTTP/2 / protobuf | 管线（pipeline） | **89,945** | 09-25 | wire 演进 |
| wire → wire | HTTP/2 / protobuf | Direct API（`DISPATCH=direct`） | **92,423** | 09-24 | wire 演进 |
| grpc-java → wire 服务端 | HTTP/2 / protobuf | 第三方客户端互操作（服务端天花板） | **104,967** | 09-24 | 互操作对照 |
| jaws → jaws（http2 腿） | HTTP/2 / fastjson2 | 管线（pipeline） | **87,350** | 09-24 | wire 演进 |
| grpc-java → grpc-java | HTTP/2 / protobuf | 正统 gRPC 参照（eager pool） | **70,334** | 09-26 | 互操作对照 |

要点：

1. **同硬件下能力分三档**：纯 TCP 的 jaws + netty（约 14 万）> 走 HTTP/2 的 wire 传输（管线 89,945、Direct API 92,423、服务端天花板约 10.5 万，jaws-over-http2 腿同量级）。netty TCP 领先 HTTP/2 的差额是 HTTP/2 帧编解码 + 流多路复用 + 应用层双层流控的固有协议税。
2. **wire 的瓶颈在客户端，不在服务端**：第三方 grpc-java 客户端打同一 wire 服务端能到约 10.5 万，而 wire 自有客户端（Direct API）打同一服务端约 9.2 万、经代理管线约 9.0 万——剩余差距在 wire 客户端核心栈（架构税），见「与 grpc-java 客户端的剩余差距归因」。
3. README 的「约 14 万」为钝表述，对应 jaws + netty 分进程稳态，继续有效。

## 序列化方式对比

**结论**：`hello(String)` 短字符串往返是序列化开销最不敏感的负载，四种序列化在同一 CPU-bound 基准下差异有限。fury 已于 09-07 合入为第四种序列化（`SERIALIZATION=fury`），实测与 fastjson2 处于同一水平、并未超越，因此未单列进下表（下表为三选手基线，fastjson2 已代表同水平的 fury）。

同一环境、同一负载（`hello(String)` 单字符串往返），20 线程、WARMUP=10s、DURATION=40s 测量，仅切换 `SERIALIZATION`（三选手基线，08-24）：

| 序列化 | QPS | 总调用 | 与 fastjson2 差异 |
|--------|------|--------|--------------------|
| fastjson2 | 96,308 | 3,852,314 | 基线 |
| protostuff | 97,615 | 3,904,597 | +1.4% |
| hessian2 | 88,780 | 3,551,219 | -7.8% |

三者均为 40s 长跑 0 错误（含返回值校验），且同条件可对比。参考：10s 短窗口跑分普遍略高（fastjson2 两次 100,408 / 100,651），测量窗口拉长到 40s 后各序列化收敛到稳定区间（与 fastjson2 60s 长跑的 98,202 一致），因此以 40s 同条件数据为准。

分析：该负载参数与返回值均为短字符串，帧头、网络回环、线程调度等固定开销占主导。同条件下 protostuff 与 fastjson2 基本持平（+1.4% 落在波动区间内），两者都是批量字节操作；hessian2 低约一成，它是三者中唯一原生流式的（无包装层开销），但 hessian-lite 逐字节流式解析的指令开销更高，在小负载下反而最慢。另外极小负载下，protostuff 包装层每对象的类名前缀占比偏高，抵消了部分二进制优势。protostuff 的真正优势场景是字段多、嵌套深的 POJO（体积紧凑、无反射逐字段解析），建议结合大对象负载进一步对比。

## jaws + netty 吞吐演进

**结论**：分进程才是 jaws 的真实性能，8 核下天花板约 14 万 QPS 峰值 / 14 万稳态均值（约 17.6k QPS/核）。承重改动是写路径异步化（09-06）与解码器头部单次解析 + 传输层清理（09-07）。以下按时间列出基线、优化与复验，08-23 / 08-24 为「第二跃（写路径异步化）」之前的历史基线。

### 历史基线（2026-08-23）

环境：macOS aarch64，8 核，单 JVM（provider + consumer 同进程，`ROLE=all`），jaws + netty，fastjson2，WARMUP=5s，DURATION=10s。同进程内仍走真实 TCP 回环（经本机网卡 IP，非进程内直调），且所有请求共享单条连接多路复用（lsof 可见同一 PID 同时持有 LISTEN 与连接两端）。

背景（jaws + netty 的两次大跃迁）：本表 20 线程峰值 100,408 是**第一跃——零拷贝改造**的成果，较改造前的 50,836 提升 **+97.5%**；**第二跃——写路径异步化**在 09-06 一节把吞吐再抬到约 13.7 万。本节数值均为第一跃之后、第二跃之前的记录。

| 线程数 | QPS | 说明 |
|------|-------|---------|
| 4 | 62,314 | 已达峰值的 62% |
| 10 | 93,694 | 已达峰值的 93% |
| 16 | 97,363 | 与 20 线程仅差 3%，无锁竞争迹象 |
| 20 | 100,408 | 峰值，约 12.5k QPS/核；同条件复验 100,651 |
| 25 | 98,785 | 超配核数，上下文切换损耗 |

结论：8 核下吞吐天花板约 10 万 QPS，最佳并发点在 16~20 线程；扩展曲线单调爬升后平稳回落，属于干净的 CPU 饱和形态。（本节为 09-06 优化前的历史值。）

长跑确认（20 线程，DURATION=60s）：QPS 98,202，共 5,892,122 次调用，0 错误（含返回值校验）；Avg 203 us，P99 388 us，P99.9 749 us，Max 6.7 ms。长时间压测无吞吐衰减、无串响应、无框架异常。

### 写路径异步化与复验（2026-09-06）

jaws + netty + fastjson2，20 线程、WARMUP=5s、DURATION=40s：

| 场景 | QPS | 说明 |
|------|-----|------|
| 优化前（08-27 基线） | 97,792 | 业务线程 `writeAndFlush` 后 `awaitUninterruptibly` 等 flush，一个请求阻塞两次（等写 + 等响应） |
| 优化后 · 同进程 ×4 | 138,209 / 138,322 / 134,864 / 135,943 | `ROLE=all`，provider + consumer 同 JVM |
| 优化后 · 分进程 ×4 | 129,287 / 136,217 / 139,210 / 136,975 | provider/consumer 独立 JVM 直连；首跑 129,287 偏低为冷启动差异，仅重启 consumer 的后三轮 13.6 万–13.9 万 |

峰值 **139,210**；剔除冷启动首跑后 8 轮稳定区间 13.5 万–13.9 万，同进程与分进程口径一致（均为真实 TCP 回环）。新天花板：8 核约 **13.9 万 QPS 峰值 / 13.7 万 稳态**（约 17k QPS/核）。

改动内容：**写路径异步化**——`NettyClient` 移除 `writeAndFlush().awaitUninterruptibly()`，改为 `addListener` 异步处理；写失败经 `responseFuture.completeExceptionally` 送达调用方，不再同步抛出。

### 峰值复验与口径结论（2026-09-07）

jaws + netty + fastjson2，20 线程、WARMUP=5s、DURATION=40s，各 5 轮：

| 口径 | 5 轮 QPS | 均值 | 峰值 |
|------|----------|------|------|
| 同进程（`ROLE=all`） | 141,167 / 137,758 / 139,148 / 138,770 / 138,541 | 139,077 | 141,167 |
| 分进程（provider 长驻） | 141,659 / 142,876 / 141,394 / 137,579 / 139,950 | **140,692** | **142,876** |

**口径结论：分进程略高于同进程（均值 +1.2%），分进程才是 jaws 的真实性能**。机制上有三层原因：

1. **GC 隔离**——同进程 provider + consumer 共享一个堆，双侧分配率叠加使 Young GC 更频繁，且每次停顿同时冻结两个角色，损失双倍；分进程两个独立小堆，停顿错峰且各伤一边。
2. **JIT 竞争**——同进程 JVM 的 C2 编译线程要与 40+ 活跃线程（20 consumer + provider worker 池）争抢 8 核。
3. **provider 长驻即生产形态**——分进程模式下服务端跨轮次保持 JIT 全热，真实部署的服务端本就是长驻进程；同进程模式每轮都是新 JVM，服务端仅靠 WARMUP 预热，系统性低估。

本轮峰值 142,876 较 09-06（139,210）再涨 +2.6%，归因当日改动：`NettyDecoder` 头部单次解析（解析完 16 字节头只向下游传 body，`JawsCodec.decode` 不再重复解析头）+ 传输层清理（无用字段/局部变量化）。CPU-bound 基准下，每请求省一次解析直接折进吞吐。

新天花板口径：**8 核约 14 万 QPS 峰值 / 14 万 稳态均值**（约 17.6k QPS/核，分进程）。README 的「约 14 万」为钝表述，继续有效。

## 传输层与 wire 演进

**结论**：HTTP/2 传输（http2 / wire）约在 7.4 万起步，经服务端合批、客户端帧折叠、Direct API 三级抬升，wire 管线消费端达 89,945（09-25 复测，约 9.0 万）、Direct API 约 9.2 万；同窗口内 netty TCP 明显领先 HTTP/2。以下先给传输层横向对比（历史基线），再列 wire 侧逐日演进。

### 传输层对比：netty vs http2 vs wire（2026-08-27）

同一环境，20 线程、WARMUP=5s、DURATION=40s，jaws 协议 + fastjson2 序列化，仅切换传输层；wire 协议使用 protobuf 序列化：

| 传输层 | 协议 | QPS | Avg | P50 | P99 | P99.9 |
|--------|------|-----|-----|-----|-----|-------|
| netty (TCP) | jaws | 97,792 | 204 us | 197 us | 381 us | 662 us |
| http2 | jaws | 73,579 | 272 us | 272 us | 445 us | 2,076 us |
| wire (gRPC wire format) | wire | 73,842 | 271 us | 264 us | 484 us | 1,988 us |

三种传输均为 40s 长跑 0 错误。

分析：

- **http2 与 wire 性能几乎一致**（~73.5k QPS），因为两者底层都是 HTTP/2 多路复用，差异仅在 gRPC 5 字节长度前缀帧（可忽略）。
- **netty TCP 比 HTTP/2 高约 33%**（97.8k vs 73.6k），平均延迟低 ~68us。开销来自 HTTP/2 帧编解码（HEADERS/DATA 帧组装拆解）和 stream 多路复用的管理成本。
- **尾延迟差距更大**：netty P99.9 为 662us，http2/wire 约 2ms（3 倍），说明 HTTP/2 帧处理在偶发毛刺时开销更明显。
- 对于追求 gRPC 互操作的场景，wire/http2 的 ~73k QPS 已是合理水平；对于纯内网高性能场景，netty TCP 仍是首选。

### wire 写路径合批与分进程基线（2026-09-24）

改动：连接 pipeline 在 `ssl` 与 `http2_codec` 之间加装 `FlushConsolidationHandler(64, true)`（commit `1bac5643`）。wire 的响应写全部来自业务线程的逐消息 `writeAndFlush`，不在 `channelRead` 期间，`consolidateWhenNoReadInProgress=true` 使 event loop 一轮内的多次 flush 收敛为一次；`handlerRemoved` / `channelInactive` 均会补刷 pending 写，连接关闭不丢帧。两个 HTTP/2 传输基类（`AbstractHttp2Server` / `AbstractHttp2Client`）同时生效。

jaws + wire + protobuf，20 线程、WARMUP=5s、DURATION=40s，各轮 0 错误（含返回值校验）：

| 口径 | QPS | 说明 |
|------|-----|------|
| 同进程，改动前（08-27 基线） | 73,842 | 上方 08-27 传输层对比表 |
| 同进程，改动后 ×2 | 77,898 / 76,696 | 合批收益约 +4~5.5% |
| 分进程 ×5 | 82,293 / 82,128 / 79,604 / 82,471 / 81,521 | 均值 **81,603**，极差 3.5% |

**wire 新基线口径：分进程均值约 8.2 万 QPS**（较 08-27 基线累计 +10.5%）。分进程 5 轮紧贴均值、无同进程 08-27 测量中出现的偶发毛刺形态；本轮同窗口未复测同进程，分进程与同进程的差值不单独归因。25 线程复测 ×2（82,371 / 81,116）与 20 线程持平，**20 线程即达饱和**，8.2 万为当前配置的分进程吞吐天花板。参考：09-07 在 jaws + netty 上分进程对同进程的增益为 +1.2%，wire 的分配面更宽（每流 child channel、帧对象、ByteBuf），分进程收益是否更大待同窗口复验后再下结论。

**客户端写路径优化（END_STREAM 折叠与消费端直连，2026-09-24）**：wire 客户端每次 unary/server-streaming 调用发出 HEADERS + DATA + 空 DATA(END_STREAM) 三帧，比 grpc-java 客户端多一帧。两步优化：① unary/server-streaming 的调用点 `sendMessage + halfClose` 背靠背同线程执行，消息帧暂存到 half-close 时折叠 END_STREAM 一次写出（3 帧 → 2 帧，与 grpc-java 同形）；client-streaming/bidi 为交互形态保持逐消息立即写出（暂存会死锁交互式流，已有拦截器测试钉住）；② 消费端新增 `DISPATCH=direct`——benchmark 客户端绕过 ReferenceConfig 代理层直接 `ManagedChannel.unaryCall`（服务端保持管线模式不动，对比口径公平）。

| 消费端 → 服务端（分进程长驻，服务端始终管线模式） | QPS | 说明 |
|------|-----|------|
| wire 代理消费端（改动前） | 81,603 | 上表 5 轮均值 |
| wire 代理消费端（折叠后 ×3） | 87,333 / 87,466 / 87,434 | 均值 **87,411**，极差 0.2%，帧折叠 +7.1% |
| wire Direct API 消费端（ManagedChannel，×3） | 92,322 / 91,548 / 93,400 | 均值 **92,423**（最新窗口复测），代理层开销约 3.6k |

距 grpc-java 客户端打同一服务端的约 105k 还差 ~12k，剩余差距在 wire 客户端核心栈（WireClient 每请求的定时器调度、响应处理与任务数）与 grpc-java 客户端栈之间，待热点采样归因（见「与 grpc-java 客户端的剩余差距归因」）。

### 同日附加改动与采样结论（2026-09-24）

- **RST 透传**：wire 客户端两个响应 handler 收 `Http2ResetFrame` 立即以映射状态失败调用（CANCEL→CANCELED、REFUSED_STREAM→UNAVAILABLE、ENHANCE_YOUR_CALM→RESOURCE_EXHAUSTED、余 INTERNAL），不再等满请求超时。e2e：对端回 RST(CANCEL) 后 0.19s 失败（原行为等满超时）。
- **8MiB 流窗口**：wire 两端广告 `SETTINGS_INITIAL_WINDOW_SIZE=8MiB`（对齐 Triple），e2e 断言 SETTINGS 交换后双向生效。小消息基准无感，解锁大负载/高 RTT。
- **每请求超时调度器**：`HashedWheelTimer` → `ScheduledThreadPoolExecutor`（removeOnCancelPolicy）。JFR 采样显示 `HashedWheelTimeout.remove()` 占 ~10%（wheel 桶为链表，取消是 O(桶长) 遍历且随待决量恶化）；**但替换后吞吐无提升**（jaws/netty 139.5k vs 存档 140.7k 持平，wire 管线 89.0k vs 87.4k 为噪声级）——该热点跑在业务线程上，而瓶颈不在业务线程 CPU。**热点 ≠ 关键路径**。保留此改动的理由是规模保险：O(桶长) 取消随待决量恶化，removeOnCancelPolicy 与待决量无关。剩余热点（HPACK 字符串比较、netty Promise 分配、pipeline 遍历）均为 netty 内部成本，grpc-java 客户端同样在付，进一步追猎边际收益低，到此收手。

**http2 腿（jaws 协议）同步受益**：FCH 合批、header 路径 AsciiString 化与 8MiB 窗口对两条 HTTP/2 腿同时生效。管线消费端三轮 85,341 / 87,843 / 88,867（均值 **87,350**，较 08-27 基线 73,579 **+18.7%**，极差 4%）——与 wire 腿管线消费端的 87.4k 持平（wire Direct API 消费端 91k 的差值即代理层，见上表）。

### 与 grpc-java 客户端的剩余差距归因（2026-09-24，JFR 双向对比）

同一 wire 服务端、同为 grpc 形态负载、各对 consumer JVM 采样（jcmd JFR.start + `jfr view hot-methods`）。wire 客户端 91k 档 vs grpc-java 客户端 105k 档，每单位工作的 CPU 结构差异：

| 成本项 | wire 客户端 | grpc-java 客户端 | 来源 |
|------|------|------|------|
| HPACK/头字符串比较 | ~23%（`AsciiString.hashCode`+`contentEquals`） | ~4% | wire 的 `WireMetadata` 逐 header 过滤与 String 传参 |
| pipeline 遍历 | 8.3%（`findContextIn/Outbound`） | 0% | wire 每 RPC 一个 netty child channel；grpc-java 是连接级 handler + stream map |
| netty Promise 机器 | ~11% | 4.7% | child channel 的 promise 链更长 |
| 写缓冲合批 | —（折叠后 2 次 ELG 任务） | 6.2%（`CoalescingBufferQueue`） | grpc-java 写路径自带合批缓冲 |

两项处置：

1. **头字符串操作已优化**——`WireConstants` 头名/值常量 AsciiString 化（HPACK 编解码走字节快路径、预计算哈希）、`WireMetadata.isReserved(CharSequence)` 无分配判断、`estimateHeaderSize` 免 `toString()+getBytes()` 分配；复采确认 AsciiString 热点从前 23% 退出 top 榜。
2. **child-channel 架构税（pipeline 遍历 + Promise 链）记档接受**——这是选择 netty `Http2MultiplexHandler` 底座的固有代价，grpc-java 的连接级 handler + WriteQueue 是另一条架构路线，追平它等于重做客户端底座，超出收益区间。

## 第三方对照与互操作

**结论**：以 grpc-java 客户端打 jaws wire 服务端做互操作反验，实测服务端天花板约 10.5 万；与正统 gRPC 的同口径对照给出横向坐标（Dubbo 系对照见文末引用）。以下含互操作命令、结果表与服务端线程模型诊断。

### grpc-java 客户端互操作与正统 gRPC 参照（2026-09-24）

新增 `./run-sample.sh bench-grpc`（grpc-java 1.83.1 客户端，`SERVER=wire` 打 jaws wire 服务端做互操作反验，`SERVER=grpc` 打纯 grpc-java netty 服务端做参照，参照服务端业务池对齐 wire：有界 EagerThreadPool 20/200/queue0；手搓 `MethodDescriptor` 对齐 wire 注册路径，proto 零改动）。20 线程、WARMUP=5s、DURATION=40s，各轮 0 错误：

| 客户端 → 服务端 | QPS | 说明 |
|------|-----|------|
| grpc-java → grpc-java（正统 gRPC，分进程长驻，eager pool） | 69,711 / 71,343 / 69,949 | 均值 **70,334**，极差 2.3%（09-26 安静环境复测，与下文穿越税分解同源） |
| wire 客户端 → wire 服务端（管线） | **87,411** | 09-24 帧折叠后管线消费端均值（极差 0.2%） |
| grpc-java → wire 服务端（互操作，分进程长驻） | 103,851 / 105,544 / 105,506 | 早期窗口均值 102,031，最新窗口三轮均值 **104,967** |

两点结论：

1. **wire 服务端吞吐上限实测约 10.5 万**——第三方 grpc-java 客户端多轮稳定复验（0 错误），远高于 wire 管线客户端打出的约 8.7 万（87,411），剩余瓶颈在 wire 客户端核心栈（见「与 grpc-java 客户端的剩余差距归因」）。
2. **grpc-java 全套语义（HPACK、trailers、deadline、流控）在 wire 服务端上全绿**，互操作性经第三方客户端反向验证成立。互操作与正统 gRPC 的差值（约 10.5 万 vs 约 7.0 万）两侧 provider 均为长驻预热后测得，但预热时长仍不对称（数小时 vs 分钟级），作为方向性结论参考，精确差值待同预热条件复验。

**服务端框架派发税 ≈ 0（A/B 实测，2026-09-26 安静环境）**：为验证 wire 服务端的框架栈是否拖慢吞吐，把同一 grpc-java 客户端分别打两种服务端起法——Path A 用裸 `WireServer` + `WireHandlerRegistry` 注册直返的 `WireMethodHandler`（绕开框架），Path B 用 `ServiceConfig.export()` 全栈（WireExporter → WireMessageHandler → resolveService/getMethodInfo + invoker/代理/反射派发）。各 3 轮、20 线程、DURATION=40s、0 错误：

| 服务端起法 | QPS ×3 | 均值 | 极差 |
|---|---|---|---|
| Path A · 裸 handler（绕框架） | 106,626 / 106,769 / 105,773 | 106,389 | 0.9% |
| Path B · ServiceConfig 全栈 | 105,946 / 107,354 / 106,273 | 106,524 | 1.3% |

两者差 0.13%、被各自极差吞没——**在 `hello(String)` CPU-bound 天花板这一档，服务端框架派发税实测为 0**。机制与「热点 ≠ 关键路径」同源：真正吃 CPU 的是 event loop + HTTP/2 帧编解码 + protobuf + 单次派发，而 invoker 派发不过是一次 map 查找 + 反射调用，摊进 ~20us 的单请求不足 0.5us，测不出。（注：此处 Path B 是 09-26 安静环境复测，106,524 略高于上表 09-24 的 104,967，属环境红利，与 A/B 结论无关。）复现：临时把 `GrpcBenchmark.exportWireService()` 改成 `WireHandlerRegistry` + `new WireServer(url, registry).open()`（同 `sample-wire-interop` 的 `GrpcCallWireDemo` 起法），A 用 `SERVER=wire ROLE=provider bench-grpc`、B 用 `ROLE=provider bench-wire`，客户端恒为 `SERVER=wire ROLE=consumer bench-grpc`；该实验代码不随仓保留。

**服务端线程模型诊断（jstack + 对照实验）**：grpc-java netty 服务端为 boss ELG 1 线程 + worker ELG（netty 默认 2×核数，单连接实际只有 1 条活跃）+ 无界 cached executor（`grpc-default-executor`，压测中 7 秒生灭 23 条线程），每个 RPC 在 event loop 与 executor 间跳两次，连接的全部编解码串在单条 ELG 线程上（实测单核占用 ~73%）。围绕线程池形态做了三组测量：默认无界 cached executor（基线 64,838）、`directExecutor()`、与 wire 同款的有界 EagerThreadPool（20/200/queue0）。**正统 gRPC 参照口径现统一取 eager pool**（对齐 wire 服务端形态，09-26 安静环境复测 70,334，见上表），另两组对照均未合入：

- `directExecutor()`（回调直接跑在 ELG，零跳变）：cached 基线 64,838 → 92,906 / 85,738，**4 成开销在线程往返本身**；
- 有界 EagerThreadPool 作为参照标准口径：09-26 安静环境 3 轮 69,711 / 71,343 / 69,949（均值 **70,334**，取代此前噪声窗口的 67,883）。固定线程消掉生灭、吞吐较 cached 基线略增，但消不掉 event loop↔executor 往返——那要 `directExecutor()` 才拿得回。

**互操作与正统 gRPC 的约 32k 差距分解**（两边客户端相同——均为 grpc-java，变量只剩服务端：wire 服务端 102,031 vs grpc-java 服务端 eager pool 70,334）：

- **约 23k：回调穿越层**。directExecutor 对照把这块钉死（92,906 − eager 基线 70,334 ≈ 22.6k；directExecutor 那格取自其对照实验原窗口，与 09-26 eager 属跨窗口，此差值为估算）。grpc-java 服务端每 RPC 的回调链是三层包装——`JumpToApplicationThreadServerStreamListener`（跳线程 + Context 传播，ServerImpl.java:791）、`SerializingExecutor`（回调可重入串行化，:479）、再过一层 switchingExecutor（:591）——多次 executor 提交才落到用户线程池；wire 服务端是单次 `serverExecutor.execute(dispatch)`，业务线程解码+调用+编码+写回一条龙。这层是通用框架为"任意 executor 下线程语义正确"付出的结构性成本。
- **约 9k：通用机器差异**（此时两边线程形态已对齐）。grpc-java 每 RPC 的 Metadata 双向解析转换、Context 生命周期、ServerCall/trailers 状态机、PerfMark 埋点等通用开销，对比 wire 的精简派发路径；外加 SETTINGS 差异（grpc-java 窗口 1MB vs wire 64KB，对本负载影响小）与预热不对称残余。此块的精确归因需对服务端采样热点对比，列为方向性结论。

反过来说，这约 23k 正是 wire 单次派发设计的价值证明：它省掉的不是线程池本身，而是每 RPC 必付的"回调穿越税"。

生产建议：默认无界 cached pool 在突发下会无限扩线程，应换成有界池（吞吐不损失）；但要拿回线程往返那 4 成，只能 `directExecutor()` 且业务 handler 必须非阻塞——这正是 wire 服务端的形态（event loop 做轻活、重活一次性派发），其 10.5 万与该诊断自洽。

附带教训：bench-grpc 首跑曾钉死在约 1.5 万 QPS——benchmark 模块原本没有 `logback.xml`，logback 缺省 root=DEBUG，grpc-java 的 `NettyClientHandler` 对每个 HTTP/2 帧在 event loop 上同步打 DEBUG 日志，日志开销成了瓶颈。已加配置将 `io.grpc` 门禁到 INFO。

## 服务端记账改动复测（2026-09-25）

`ded374ec` 与 `0d8a63a6` 把服务端几处"只被声明不被执行"补实：accept 路径加了限连计数（`maxServerConnections` 落地）、每流路径加了 inflight 计数（`drainInflightRequests` 从此对 wire 真正生效）、反射 bidi 流补了终止帧。三者都贴着吞吐关键路径，故在停掉 IDEA 与办公类应用后的安静窗口重测 wire 管线腿——分进程长驻（`ROLE=provider` 常驻 + `ROLE=consumer DISPATCH=pipeline`），20 线程、WARMUP=5s、DURATION=40s：

| 轮次 | QPS | 总调用 |
|------|-----|--------|
| 第一轮（作预热，不计入） | 89,054 | 3,562,179 |
| 第二轮 | 90,541 | 3,621,657 |
| 第三轮 | 89,524 | 3,580,953 |
| 第四轮 | 89,770 | 3,590,809 |

有效三轮均值 **89,945**，极差 1.0k（1.1%）。对照 09-24 帧折叠后的 87,411（极差 0.2%）是 **+2.9%**；与同日附加改动一节里 STPE 替换后测得的 89.0k 同档，因此读作**吞吐无损**，那 +2.9% 不解读为提升——跨窗口比较，量级仍落在环境噪声内。

未覆盖：Direct API 消费端（92,423）与 grpc-java 互操作（104,967）本轮未重测。服务端改动对三条腿同等生效，但按"改哪测哪"的纪律，那两格的口径值仍沿用 09-24 记录。

## 关于 Dubbo / Triple 的对照

本文正文不设 Dubbo / Triple 的对照行，原因有二：其一，jaws 的全部数字出自自研 harness（`run-sample.sh`、手写线程循环、`hello(String)` 单字符串极小报文），而 Apache 官方基准走的是 JMH + 结构化 `User` DTO，两者的测量方法、线程数、报文尺寸与序列化敏感点均不同，绝对值放进同一张表会构成假可比；其二，此前一次性记录过的 Dubbo Triple 跑分出自一个未随仓保留、无法复现的临时 harness，不满足本文对可复现口径的要求，故一并撤下。

作为外部坐标，仅引 Apache 官方结论：Dubbo 3.2 的 Triple 协议相较 3.1，在小报文场景（`existUser` / `getUser` / `createUser`）性能提升约 40%–45%，提升后与同场景 gRPC 基本持平；较大报文场景（`listUser`）较 3.1 提升约 17%，仍比同场景 gRPC 低约 11%。此处只作 Triple 与 gRPC 的**协议层相对位置**参考，不与上文任何 jaws 绝对值直接比较。

来源：Apache Dubbo 官方基准工程 [apache/dubbo-benchmark](https://github.com/apache/dubbo-benchmark)（JMH，`dubbo-triple` 对 `native-grpc`）及 Dubbo 官方性能文档。
