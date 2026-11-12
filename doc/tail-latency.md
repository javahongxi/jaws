# 尾延迟归因（P99.9 毛刺）

本文是尾延迟的**归因方法论 + jaws 实测案例复盘**。所有数字的口径事实源是 [benchmark.md](benchmark.md)，本文只引用其结论与章节，不另立数字台账；凡涉及具体 QPS / 分位值，均以彼处标注的采集日期与配置为准。

## 一、为什么看 P99.9，而不是平均值

平均值回答"典型请求多快"，P99.9 回答"最倒霉的千分之一请求多慢"。毛刺恰恰藏在尾部：GC 停顿、线程调度、锁竞争、协议栈偶发开销，在平均值里被稀释到看不见。

尾延迟还有**扇出放大效应**：一次上层请求若扇出 N 个 RPC，至少命中一次 P99.9 的概率为 \(1-(0.999)^N\)——N=100 时约 9.5%，N=1000 时约 63%。所以尾延迟才是用户真实体感，而不是平均值。

jaws 的实测形态非常典型（数据出处：[benchmark.md](benchmark.md)「传输层对比：netty vs http2 vs wire」节，2026-08-27，20 线程 / WARMUP=5s / DURATION=40s）：

| 传输 | Avg | P99 | P99.9 |
|------|-----|-----|-------|
| netty TCP | 204 us | 381 us | **662 us** |
| http2 | 272 us | 445 us | **2,076 us** |
| wire | 271 us | 484 us | **1,988 us** |

平均只差 1.33 倍，P99 差 1.17 倍，**P99.9 差到约 3 倍**——协议税在尾部被放大，这正是"毛刺归因"要解释的现象。

两个统计细节值得记住：

- **P99.9 是稳定统计量**。40s 窗口下尾部样本数以千计（10 万 QPS × 40s × 0.1% ≈ 4000 个样本），分位值有据可依。
- **Max 是单点事件**。60s 长跑记录里 Max 6.7ms，比 P99.9（749us）高一个量级，但单个极值可能只是一次 OS 调度抖动，归因要克制，别拿单点当结论。

## 二、归因动线：先验测量 → 分层定位 → 对照实验钉死

### 第 0 步：怀疑测量本身

尾延迟归因的第一嫌疑人往往是测量方法，而不是框架：

- **Coordinated omission**：闭环压测（jaws 的手写线程循环即属此类）中，线程被卡住时少发了请求，尾延迟会被系统性低估。解读分位值前先确认 harness 形态。
- **跨窗口差值不单独归因**：不同日期的两次测量受环境噪声影响，差值须同窗口复测后才可归因（benchmark.md 明文纪律）。
- **环境噪声**：后台 IDE、办公应用会污染尾延迟。09-25 复测专门停掉 IDEA 与办公类应用后取安静窗口。
- **预热不对称**：长驻服务端（JIT 全热）与新起 JVM 的对比只能下方向性结论，精确差值待同预热条件复验。
- **0 错误含返回值校验**：排除串响应、丢请求造成的假延迟，是尾延迟数据可信的前提。

### 分层定位

确认测量可信后，按层排查，每层有对应工具：

| 层 | 典型毛刺源 | 定位手段 |
|----|-----------|---------|
| JVM | Young GC 停顿、JIT 编译/去优化、safepoint | JFR `jdk.GCPhasePause`、GC 日志 |
| 线程 | event loop 被阻塞、业务池排队、线程生灭、上下文切换 | jstack、JFR `jdk.ThreadPark`、**wall-clock 采样** |
| 协议 | HTTP/2 帧编解码、HPACK、流控窗口、多路复用管理 | JFR 热点 + A/B 对照 |
| OS | 调度抖动、CPU 频率、后台进程 | 安静窗口复测、pidstat |

关键一点：**尾延迟往往在 off-CPU**。CPU 热点采样只能看到"谁在烧 CPU"，看不到"谁在等"。async-profiler 的 wall-clock 模式、JFR 的 park/monitor 事件，才是尾延迟归因的主武器。

### 对照实验钉死因果

定位到嫌疑后，用 A/B 对照把相关性变成因果：只改一个变量、同窗口复测、看分位值是否随之变化。jaws 的每个归因结论都有对应的对照实验（见下节）。

## 三、jaws 已钉死的归因案例

### 1. GC 耦合——同进程毛刺的经典来源

同进程 provider + consumer 共享一个堆，双侧分配率叠加使 Young GC 更频繁，且每次停顿**同时冻结两个角色**，损失双倍。分进程后两个独立小堆，停顿错峰、各伤一边。

对照证据：09-24 的 wire 分进程 5 轮记录明确写着"无同进程 08-27 测量中出现的偶发毛刺形态"（见 [benchmark.md](benchmark.md)「wire 写路径合批与分进程基线」节）。这是对照实验直接钉死的归因，不是猜测。口径结论一节还给出 JIT 竞争、provider 长驻全热两层机制。

### 2. event loop 上的同步日志——IO 线程阻塞的极端样本

bench-grpc 首跑曾钉死在约 1.5 万 QPS：benchmark 模块缺 `logback.xml`，logback 缺省 root=DEBUG，grpc-java 的 `NettyClientHandler` 对**每个 HTTP/2 帧在 event loop 上同步打 DEBUG 日志**。一条连接的全部编解码串在单条 ELG 线程上，任何阻塞都会放大成整条连接的尾延迟。将 `io.grpc` 门禁到 INFO 后恢复（见 [benchmark.md](benchmark.md)「附带教训」）。

### 3. HTTP/2 协议税的尾部放大

平均只贵约 68us，但帧组装拆解、stream 管理、双层流控这些开销在偶发时刻叠加，把 P99.9 拉到约 3 倍。JFR 双向对比钉死了结构差异（见 [benchmark.md](benchmark.md)「与 grpc-java 客户端的剩余差距归因」节）：

| 成本项 | wire 客户端 | grpc-java 客户端 |
|------|------|------|
| HPACK / 头字符串比较 | ~23% | ~4% |
| pipeline 遍历（每 RPC 一个 child channel） | 8.3% | 0% |
| netty Promise 机器 | ~11% | 4.7% |

处置分两类：

- **能修的修**：头名/值常量 AsciiString 化（HPACK 走字节快路径、预计算哈希，复采确认热点退出 top 榜）、`FlushConsolidationHandler(64, true)` 写合批、8MiB 流窗口。
- **修不动的记档接受**：child-channel 架构税（pipeline 遍历 + Promise 链）是选择 netty `Http2MultiplexHandler` 底座的固有代价，追平 grpc-java 的连接级 handler + WriteQueue 等于重做客户端底座，超出收益区间。

### 4. 线程往返与生灭

grpc-java 服务端每 RPC 的回调链穿越三层 executor 包装才落到用户线程池，压测中 7 秒生灭 23 条线程；`directExecutor()` 对照拿回约 4 成吞吐（见 [benchmark.md](benchmark.md)「服务端线程模型诊断」节）。线程生灭与调度抖动正是尾延迟的来源之一。wire 服务端单次 `serverExecutor.execute(dispatch)` 的一条龙派发，省掉的正是这层"回调穿越税"。

## 四、热点 ≠ 关键路径

JFR 曾显示 `HashedWheelTimeout.remove()` 占 ~10%（wheel 桶为链表，取消是 O(桶长) 遍历且随待决量恶化）。换成 `ScheduledThreadPoolExecutor`（removeOnCancelPolicy）后**吞吐纹丝不动**——因为该热点跑在业务线程上，而瓶颈不在业务线程 CPU（见 [benchmark.md](benchmark.md)「同日附加改动与采样结论」节）。

这个案例对尾延迟归因同样成立：**看到热点不等于找到根因，必须用对照实验验证因果**。保留该改动的理由是规模保险（O(桶长) 取消随待决量恶化，removeOnCancelPolicy 与待决量无关），而不是虚假的性能收益。诚实区分"修了有用"与"修了只是保险"，是归因的高级纪律。

## 五、归因纪律小结

把上面的动线收敛成一份可执行 checklist：

1. **先验测量**：确认 harness 形态（闭环有 coordinated omission）、同窗口复测、安静环境、预热对称、0 错误含返回值校验。
2. **分层定位**：JVM → 线程 → 协议 → OS；尾延迟优先看 off-CPU（wall-clock / park 事件），不只盯 CPU 热点。
3. **对照钉死**：只改一个变量，看分位值是否随之变化；跨窗口差值不单独归因。
4. **处置分类**：能修的修，修不动的记档接受，噪声级的不解读为提升。
5. **统计克制**：P99.9 有数千样本支撑可下结论；Max 是单点，不轻归因。
6. **热点 ≠ 关键路径**：CPU 热点须验证是否在瓶颈线程上，再谈因果。

一句话：**P99.9 毛刺归因的本质，是用对照实验把相关性变成因果。** [benchmark.md](benchmark.md) 已是一份现成的归因案例集——GC 耦合、日志阻塞、协议税、线程穿越，每一个都有数据、有实验、有处置结论。

## 相关文档

- [benchmark.md](benchmark.md) — QPS 与分位值的口径事实源，本文全部数据的出处
- [netty-performance.md](netty-performance.md) — Netty 线程模型 / 内存 / 零拷贝的高性能设计
- [http2-vs-tcp.md](http2-vs-tcp.md) — HTTP/2 帧结构与裸 TCP 的协议层对比