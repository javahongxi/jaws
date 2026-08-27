# 性能测试

项目提供 `run-sample.sh` 脚本统一管理基准测试：

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

| 变量          | 说明           | 默认值      | 适用范围      |
|-------------|--------------|-----------|-----------|
| `THREADS`   | 并发线程数        | 4         | 全部 |
| `WARMUP`    | 预热秒数         | 5         | 全部 |
| `DURATION`  | 测量秒数         | 10        | 全部 |
| `TRANSPORT`   | 传输层：`netty`（默认 TCP）或 `http2` | netty | 仅 bench-jaws |
| `PORT`      | jaws 协议端口    | 10010     | 仅 bench-jaws |
| `SERIALIZATION` | 序列化方式（fastjson2 / hessian2 / protostuff） | fastjson2 | 仅 bench-jaws |
| `SLEEP`       | Provider 端模拟业务耗时（毫秒）      | 0         | bench-jaws |
| `ROLE`        | 运行角色：`all` 同进程；`provider` / `consumer` 分进程 | all | bench-jaws / bench-wire |
| `HOST`        | provider 地址，consumer 直连目标 | 127.0.0.1 | 仅分进程模式 |
| `COMPRESSION` | 压缩方式（`gzip` 或空） | 空（不压缩） | 仅 bench-wire |

### 分进程模式说明（仅 bench-jaws）

`ROLE=all`（默认）时，provider 与 consumer 在同一 JVM 内，使用进程内 `local` 注册中心；
`ROLE=provider` / `ROLE=consumer` 时，两端分属独立 JVM，通过 `direct` 直连注册中心对接：
consumer 直接指向 provider 的 `HOST:PORT`，无需外部注册中心。用法：

1. 终端 A：`ROLE=provider PORT=10010 ./run-sample.sh bench-jaws`，等待输出 `Provider is ready`；
2. 终端 B：`ROLE=consumer PORT=10010 THREADS=20 ./run-sample.sh bench-jaws` 执行压测；
3. 压测结束后 Ctrl+C 停止 provider。

分进程模式下两侧必须保持 `PORT`、`SERIALIZATION`、group/version 一致（后两者为固定值）。

## 参数选择建议

| 场景 | 线程数 | 测量秒数 | 说明 |
|------|--------|---------|------|
| 快速验证 | 4 | 10 | 默认值，确认功能正常 |
| 常规压测 | 8~16 | 10 | 观察中等并发下的表现 |
| 极限吞吐 | 32~64 | 20 | 探索 QPS 天花板 |
| 模拟业务（有 sleep） | 8~16 | 20~30 | 单次调用慢，需更多时间积累样本 |

> QPS = 总调用次数 / 测量秒数。并发线程数决定"同时有多少请求在飞"，线程越多 QPS 越高，直到达到系统瓶颈。

## 性能基线（2026-08-23）

环境：macOS aarch64，8 核，单 JVM（provider + consumer 同进程，`ROLE=all`），jaws + netty，fastjson2，WARMUP=5s，DURATION=10s。同进程内仍走真实 TCP 回环（经本机网卡 IP，非进程内直调），且所有请求共享单条连接多路复用（lsof 可见同一 PID 同时持有 LISTEN 与连接两端）。

| 线程数 | QPS | 说明 |
|------|-------|---------|
| 4 | 62,314 | 优化前约 5.7 万，优化后 +9% |
| 10 | 93,694 | 已达峰值的 93% |
| 16 | 97,363 | 与 20 线程仅差 3%，无锁竞争迹象 |
| 20 | 100,408 | 峰值，约 12.5k QPS/核；同条件复验 100,651 |
| 25 | 98,785 | 超配核数，上下文切换损耗 |

结论：8 核下吞吐天花板约 10 万 QPS，最佳并发点在 16~20 线程；扩展曲线单调爬升后平稳回落，属于干净的 CPU 饱和形态。

长跑确认（20 线程，DURATION=60s）：QPS 98,202，共 5,892,122 次调用，0 错误（含返回值校验）；Avg 203 us，P99 388 us，P99.9 749 us，Max 6.7 ms。长时间压测无吞吐衰减、无串响应、无框架异常。

## 序列化方式对比（2026-08-24）

同一环境、同一负载（`hello(String)` 单字符串往返），20 线程、WARMUP=10s、DURATION=40s 测量，仅切换 `SERIALIZATION`：

| 序列化 | QPS | 总调用 | 与 fastjson2 差异 |
|--------|------|--------|--------------------|
| fastjson2 | 96,308 | 3,852,314 | 基线 |
| protostuff | 97,615 | 3,904,597 | +1.4% |
| hessian2 | 88,780 | 3,551,219 | -7.8% |

三者均为 40s 长跑 0 错误（含返回值校验），且同条件可对比。参考：10s 短窗口跑分普遍略高（fastjson2 两次 100,408 / 100,651），测量窗口拉长到 40s 后各序列化收敛到稳定区间（与 fastjson2 60s 长跑的 98,202 一致），因此以 40s 同条件数据为准。

分析：该负载是序列化开销最不敏感的场景——参数与返回值均为短字符串，帧头、网络回环、线程调度等固定开销占主导。同条件下 protostuff 与 fastjson2 基本持平（+1.4% 落在波动区间内），两者都是批量字节操作；hessian2 低约一成，它是三者中唯一原生流式的（无包装层开销），但 hessian-lite 逐字节流式解析的指令开销更高，在小负载下反而最慢。另外极小负载下，protostuff 包装层每对象的类名前缀占比偏高，抵消了部分二进制优势。protostuff 的真正优势场景是字段多、嵌套深的 POJO（体积紧凑、无反射逐字段解析），建议结合大对象负载进一步对比。

## 传输层对比：netty vs http2 vs wire（2026-08-27）

同一环境，20 线程、WARMUP=5s、DURATION=40s，jaws 协议 + fastjson2 序列化，仅切换传输层；wire 协议使用 protobuf 序列化：

| 传输层 | 协议 | QPS | Avg | P50 | P99 | P99.9 |
|--------|------|-----|-----|-----|-----|-------|
| netty (TCP) | jaws | 97,792 | 204 us | 197 us | 381 us | 662 us |
| http2 | jaws | 73,579 | 272 us | 272 us | 445 us | 2,076 us |
| wire (gRPC wire format) | wire | 73,842 | 271 us | 264 us | 484 us | 1,988 us |

三种传输均为 40s 长跑 0 错误。

分析：

- **http2 与 wire 性能几乎一致**（~73.5k QPS），因为两者底层都是 HTTP/2 多路复用，差异仅在 gRPC 5 字节长度前缀帧（可忽略）。
- **netty TCP 比 HTTP/2 高约 25%**（97.8k vs 73.6k），平均延迟低 ~68us。开销来自 HTTP/2 帧编解码（HEADERS/DATA 帧组装拆解）和 stream 多路复用的管理成本。
- **尾延迟差距更大**：netty P99.9 为 662us，http2/wire 约 2ms（3 倍），说明 HTTP/2 帧处理在偶发毛刺时开销更明显。
- 对于追求 gRPC 互操作的场景，wire/http2 的 ~73k QPS 已是合理水平；对于纯内网高性能场景，netty TCP 仍是首选。
