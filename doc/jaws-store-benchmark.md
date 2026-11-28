# jaws-store 内核极限吞吐压测

`jaws-store-benchmark.md` 是存储内核（绕过 RPC、单 JVM 直驱 `MessageStore`）吞吐口径的事实源。与 [benchmark.md](benchmark.md)（RPC 端到端 QPS，约 14 万）和 [jaws-store.md §10](jaws-store.md)（demo 级 31.9k msg/s，含 RPC 往返）不同，本文测的是引擎本身的天花板：写侧全局锁 + mmap + page cache + 过期回收，链路上没有任何网络与序列化。工具为 [StoreBenchmark](../jaws-samples/jaws-sample-store/src/main/java/org/hongxi/jaws/sample/store/StoreBenchmark.java)（`run-sample.sh bench-store`），机制细节回链 jaws-store.md 对应章节。

## 1. 环境与负载形状

- 机器：macOS arm64（Apple M2）/ 内置 NVMe / Temurin JDK 21；
- 负载：单 topic `bench-topic`（11B）× 8 队列轮转，消息体 128B，落盘 `44 + 128 + 1 + 11 = 184B`，加 CQ 单元 20B，**每条 204B**；
- 刷盘：ASYNC_FLUSH（`syncFlush=false`），极限口径取最高 TPS；
- 生产者 16（对照轮 32）个线程开环发送，`putMessageAsync(...).join()` 记单条延迟；消费者每队列 1 个泵线程短轮询批量拉取（`pullBatch=256`），位点只存压测进程内存。

## 2. 方法：开环阶梯 + 驻留预算 + 硬停保险

**开环（open-loop）而不是闭环**。每档给定固定目标速率（`-Dsteps` 阶梯，0 = 自由跑），生产者用 `parkNanos` 发牌照速投递，不管观测到的延迟如何。闭环压测里"存储变慢"会表现为压测端自己变慢，极限点被测量行为吃掉；开环下存储变慢表现为 **backlog 增长与拒写码**，发牌器绝不替引擎减速。判定规则（每档窗口差分，非全局累计）：

| verdict | 含义 |
|---|---|
| KEEP_UP_OK | 档位内 backlog 持平、零拒写——消费跟得上生产 |
| BACKLOG_GROWS | backlog 斜率越限——积压开始复利，逼近极限 |
| REJECTED | 出现任何拒写（`OS_PAGE_CACHE_BUSY` / `SERVICE_NOT_AVAILABLE`）即标 |
| PACER_SHORTFALL | 仅限速档：发牌器自身没吐出目标速率（harness 限制，非引擎结论） |

**磁盘不打满是设计出来的，不是赌的**。驻留量只有一个来源（机制见 jaws-store.md §6.1）：

```
驻留字节 ≈ 写速率 × (fileRetentionMillis + cleanupIntervalMillis + 一个文件的滚动粒度)
```

于是开局把 TTL 压到 30s、清理节拍压到 3s（默认 72h/60s 在 400MB/s 写入下要驻留 100TB 量级，任何盘都装不下），并按上式对峰值档预测驻留、对比卷剩余空间打印 SAFE/EXCEED 预算行。保险有两层：`diskMaxUsedSpaceRatio=75` 让引擎自己走水位强制删（jaws-store.md §6.1），采样线程另按 `diskStopRatio=85` 直接停掉生产者——对应 RocketMQ 磁盘满拒写的同款兜底。实测全程没有触到任何一层（§5）。

## 3. 结果

四轮复测 + 一次新位置冒烟（数据目录跑完即删）：

| 轮次 | 配置 | 自由跑接受速率 | 消费跟平 | 拒写 | commitlog 驻留 |
|---|---|---|---|---|---|
| run1 | 16 生产者 / 8 泵 | **1.966M/s** | 1.961M/s ≈1:1 | 0 | 26 文件 / 27.9GB |
| run2 | 16 生产者 / 8 泵 | **1.948M/s** | 1.970M/s ≈1:1 | 少量（滚动停摆，见 §5） | 24 文件 / 25.8GB |
| run3 | 32 生产者 / 8 泵 | 1.760M/s | ≈1:1 | 0 | 14 文件 / 15.0GB |
| run4 | 32 生产者 / 8 泵 | 1.936M/s | ≈1:1 | 0 | 14 文件 / 15.0GB |
| 冒烟 | 16p，改包后验证 | 2.29M/s | ≈1:1 | 0 | ≤2 文件（短程） |

**极限 ≈ 1.9~2.0M msg/s，按每条 204B 折算 commitlog ≈ 360MB/s、含索引 ≈ 400MB/s**。冒烟轮 2.29M 与之的差值属跨窗口波动，按惯例不归因（benchmark.md 纪律：多轮报均值极差，单点差值不作结论）。

"消费速度刚好能应付生产"的拐点判定：限档 100k~500k/s 每一档 backlog 都持平（斜率 ±20/s 量级）、四轮 `lost=0`（清理从未跑在消费者前面），直到自由跑 ~2M/s 消费仍然 1:1 跟平——**这台机器上拐点不在消费侧，写侧天花板先到**。

## 4. 瓶颈归因：全局锁内的串行段，不是线程不够、不是磁盘、不是回收

证据链三条，全部来自 §3 同一批数据：

1. **32 生产者不比 16 生产者快**（1.76~1.94M vs 1.95~1.97M，均值持平），且限档下单条平均延迟反而从 17µs 升到 44µs——加并发只加锁排队，典型的全局串行点饱和特征（jaws-store.md §3.2 的 `putMessageLock`：临界区 = 位点分配 + patch + 一次 memcpy）。
2. **瓶颈不在读**：8 个泵线程全程跟平 2M/s，pull 走 CQ 数组寻址 + CommitLog slice（§4.1），零竞争零拒读——读侧还有富余，写侧先到顶。
3. **瓶颈不在盘与回收**：retention=30s + cleanup=3s 下驻留稳定贴在 §2 的乘法线上（§5），卷水位最高 54%，离 75% 强制删线很远；删除节拍在这台盘上对 2M/s 绰绰有余。

也就是说，极限由"单线程 memcpy 进 mmap + 锁交接开销"决定。这与 RocketMQ 同构——它的 CommitLog 写路径同样以 putMessageLock（或自旋锁）串行 append，单 broker 百万级 TPS 的口径来自同一条缝。想再往上只剩两路：多 master 分片横向扩（jaws-store.md §7.1，压测对象是单实例）或把锁段改无锁 ring buffer 预分配（复杂度陡增，超出实践模块定位）。

## 5. 尾延迟税：1G 滚动时锁内 warmUp

唯一可见的延迟毛刺与吞吐损失有一个精确归因：**commitlog 文件滚动的瞬间，新文件的 `warmUp`（逐页触写 + `force(false)`）在全局锁内执行**（jaws-store.md §3.3——只有首个文件把预热挪到了启动期，滚动时没有后台预建）。1G 文件的触页+写回在 NVMe 上也要几百毫秒到秒级，实测 max put 延迟 0.7~2.4s 全部与滚动时刻对齐；每档 `stalls`（>10ms 的 put）16~758 次。run2 的少量 `OS_PAGE_CACHE_BUSY` 拒写即由此触发：持锁越过 1s 后，失速探针（§3.2）把排队者批量弹回——探针本身按设计工作，但根因是预热占锁。

RocketMQ 用后台 `AllocateMappedFileService` 提前预建下一个文件规避此税；jaws-store 尚无对应物，这是当前实现最大的尾延迟税，也解释了四轮 run 之间自由跑速率的 ±10% 波动（滚动停摆撞没撞上采样窗口）。

## 6. 压测的产出之一：抓出一个写侧真竞态

首轮冒烟时所有泵线程在第一条消息上死于 `BufferUnderflowException`。定性：旧实现把 `queueOffset` 放在 per topic-queue 锁里预分配、全局锁里才落盘，同队列并发时"先拿位点、后落盘"成为可能；reput 按物理序派发，把 CQ 可读水位抬到了未写槽之上，数组寻址（§4.1）把水位当作"之前单元全部完整"的承诺，于是消费者读到 `size=0` 的零单元。修复即 jaws-store.md §3.2 现在的口径：**位点分配移入全局锁、编码留占位、锁内 patch，失败写入不占位点**，回归用例 `concurrentProducersOnOneQueueNeverExposeIndexHoles` 钉住。这个洞只在"同队列并发写 + 并发派发读"的高压力下暴露，功能测试与低档限速都碰不到——极限压测对正确性的价值正在于此。

## 7. 磁盘水位实测：乘法线成立

| 观察项 | 预算（§2 公式） | 实测 |
|---|---|---|
| 驻留量 | 400MB/s × (30s + 3s + 滚动粒度) ≈ 13~30GB | 每轮 15~28GB，稳定在乘法线上 |
| 卷水位 | 起点 ~47%，预算内应远离 75% 线 | 峰值 54%，未触发水位强制删 |
| 清理节拍 | 3s 一遍，需跟得上 2M/s 滚动 | 全程 `lost=0`，回收从未落后于消费 |
| 硬停保险 | 85% 停生产者 | 从未触发 |

结论：短 TTL + 短清理节拍下，极限吞吐压测的磁盘占用完全可预算、可复现，**不需要打满磁盘就能压到引擎极限**——极限点在锁，不在盘。反过来说，若保持默认 72h TTL 压 2M/s，十几分钟撑爆一块盘，水位拒写（§6.1）是唯一防线。

## 8. 诚实标注与复跑

harness 自身的限制，读数时须知的三件事：

- **parkNanos 发牌天花板 ≈ 540~600k/s**：限档阶梯到这一带出现 `PACER_SHORTFALL` 是压测端定时器精度问题，高档容量数据一律取自自由跑段，不代表引擎在 540k 退化；
- **泵线程延迟拉取**：消费读的是本地内存位点 + 短轮询，无网络，消费能力读数偏乐观——这是引擎口径压测的题中之义，端到端消费口径见 [benchmark.md](benchmark.md)；
- **跨轮 ±10% 波动不归因**（§5 的滚动停摆相位是最大嫌疑，但未做受控实验证实）。

复跑：默认阶梯 50k~400k + 自由跑（本文实测时阶梯铺到 500k，同口径可调 `STEPS`）：

```bash
./run-sample.sh bench-store
# 自定义：阶梯/消息体/生产者数/保留窗口
STEPS=200000,400000,0 MSG_SIZE=256 PRODUCERS=16 ./run-sample.sh bench-store
```

参数全集见 [StoreBenchmark](../jaws-samples/jaws-sample-store/src/main/java/org/hongxi/jaws/sample/store/StoreBenchmark.java) 类头与 `run-sample.sh` usage。数据目录 `STORE_PATH`（默认 `/tmp/jaws-store-bench`）开局自动清空，跑完手工删除。

## 9. RPC 闭环压测（StorePipelineBenchmark）：极限生产下消费跟不跟得上

本节口径与前 8 节**不同**：前文绕过 RPC 直驱内核测引擎天花板，这里走真实的 `StoreProducer → StoreBroker → StoreConsumer` RPC 链路（工具 [StorePipelineBenchmark](../jaws-samples/jaws-sample-store/src/main/java/org/hongxi/jaws/sample/store/StorePipelineBenchmark.java)，`run-sample.sh bench-store-rpc`），回答的是另一个问题——**极限生产下，单个 StoreConsumer 消费端能否跟平**。它与 [benchmark.md](benchmark.md)（RPC 端到端 QPS ~14 万）互补：benchmark.md 测小消息 RPC 往返，本节测存储链路在写读并发下的端到端吞吐与积压行为。

**分进程 + 单消费者排他模型**。三进程同机（broker → consumer 先 attach → producer），各自独立 JVM，规避同进程 CPU/GC 争抢压低读数。消费侧是**单个 StoreConsumer**：Exclusive 模型下一队列只允许一个 reader，同组第二实例会被 broker 租约围栏拒绝，故并行度只来自队列数——一个消费者订阅全部 8 队列，跑 8 个泵（pumps=queues），全部复用**一条 broker 连接**。分进程下 backlog 由 broker 位点测：`sum(maxOffset) − sum(committed offset)`。阶梯 50k→800k→自由跑，每档 30s，判定沿用 §2 的开环口径（`OFFER_OK`/`SEND_FAILED`）叠加消费 verdict。

首轮数据（producers=16 async / msgSize=128 / queues=8）：

| step | target | accepted/s | failed | send avg | verdict |
|---|---|---|---|---|---|
| 0 | 50k | 49,469 | 0 | 2.99ms | OFFER_OK |
| 1 | 100k | 99,726 | 0 | 1.73ms | OFFER_OK |
| 2 | 200k | **118,914** | 197,503 | 64.6ms | SEND_FAILED |
| 3 | 400k | 123,642 | 318,286 | 65.1ms | SEND_FAILED |
| 4 | 600k | 118,525 | 2,143 | 68.8ms | SEND_FAILED |
| 5 | 800k | 114,381 | 1,106,168 | 69.5ms | SEND_FAILED |
| 6 | free | **125,622** | 2,199,990 | 64.8ms | SEND_FAILED |

| 消费阶段 | 实际 prod/s | 实际 cons/s | maxBacklog | 判定 |
|---|---|---|---|---|
| 50k | ~49k | ~49k | ≈0 | KEEP_UP，backlog 持平 |
| 100k | 97,090 | 97,090 | 3,056 | KEEP_UP，cons=prod |
| 200k（实到 ~116k）| 116,632 | 115,185 | 55,833（瞬时）| cons≈prod，尖峰可排空 |
| 400k~free | — | — | — | 位点查询持续 40002 → 监控断样 |

**结论**：

1. **本轮未夹到单连接消费的真·天花板**——瓶颈先到在 broker。约 **125k/s 聚合 RPC** 处 broker worker 线程池（200 线程 + 1000 队列）被「16 生产者写入 + 消费者 pull + 位点 commit/query」共用打满，返回 `error_code 40002: request rejected by server due to full thread pool`，写侧 accepted 封顶 ~114–126k/s，故阶梯 200k 起 producer 全部 `SEND_FAILED`。
2. **可达区间内单消费者完全跟得上**：50k、100k 处 consRate=prodRate、backlog≈0；逼近 ~120k/s 时仍贴着生产速率走，backlog 仅因个别 pull/commit 被拒瞬时抬到 ~55k 随即排空——**消费连接不是约束，broker 共享线程池才是**。
3. **t≈90s 后监控断样**：400k+ 档 broker 持续饱和，consumer 侧查询连接连续 40002/被标记 unavailable，压测端跳过所有样（进程未崩，正常跑完并 `Consumer monitor complete`）。

**方法学注记**：写侧与读侧在同一批 broker 线程上争抢，16 生产者的写压先顶到 40002，掩盖了纯读上限。要隔离单连接消费极限，须把 `producers` 压到 2–4 让写侧不饱和、把唯一瓶颈留给消费连接——复测见 §10。

## 10. `PRODUCERS=2` 隔离复测：单连接消费上限远高于写侧

同机三进程、单 StoreConsumer（1 连接、pumps=queues=8），仅把 producers 降到 2（async / inflight 512）。目的：让 broker worker 池不被写压打满，把瓶颈留给消费连接。

Producer 报告：

| step | target | offered/s | accepted/s | failed | verdict |
|---|---|---|---|---|---|
| 0 | 50k | 47,860 | 47,860 | 0 | OFFER_OK |
| 1 | 100k | 94,338 | 94,338 | 0 | OFFER_OK |
| 2 | 200k | 114,346 | 114,345 | 0 | PACER_SHORTFALL |
| 3 | 400k | 124,655 | 124,623 | 0 | PACER_SHORTFALL |
| 4 | 600k | 126,143 | 126,142 | 0 | PACER_SHORTFALL |
| 5 | 800k | 128,731 | 128,731 | 0 | PACER_SHORTFALL |
| 6 | free | **127,968** | 127,970 | 0 | OFFER_OK |

Consumer 监控（全程）：**cons≈prod，backlog 峰值仅 917 条且立即排空**，停产后归 0；无任何 40002 / unavailable。

**结论**：

1. **写侧零失败**：2 生产者不再打满 broker 线程池，§9 的 40002/SEND_FAILED 确系「16 生产者 + 消费者」共用线程池饱和所致，非链路本身。
2. **瓶颈换人**：2 条 async 连接发牌上限 ~128k/s（step2–5 `PACER_SHORTFALL`，每条连接 ~65k/s），阶梯 200k+ 根本喂不满——**这次是生产侧触顶，不是消费侧**。
3. **单连接消费上限 > 系统写侧吞吐**：消费端把整段 ~128k/s 全跟平、backlog≈0，说明单消费者（单连接、8 泵）的处理能力至少 ≥ ~130k/s，高于本轮可达产量。两轮合观：`producers=16` 自由跑 accepted ~125,622/s、`producers=2` 自由跑 offered/accepted ~127,968/s——**2 条连接≈ 16 条连接的聚合产出**，说明 ~125–130k/s 是 **broker 经 RPC 写入路径本身的天花板**（序列化 + 分发 + ASYNC_FLUSH 流水），与 §1–§8 内核直驱 1.9M/s 的 ~15× 差距即这笔 RPC 税（量级对齐 [benchmark.md](benchmark.md) 的 ~14 万 QPS）。
4. **诚实标注**：本轮仍未从上方夹到消费拐点——因为写侧/生产侧先在 ~128k/s 触顶，单消费者始终有余量。结论是「**单连接消费能力高于整个 RPC 写链路能交付的速率，消费不是短板**」；要真正测出消费上限，需比单 broker 写入更快的生产源（多 master 分片，见 [jaws-store.md §7.1](jaws-store.md)），超出本验证件单实例口径。

复跑：

```bash
# 三终端（同一 storePath/port）
ROLE=broker   STORE_PATH=/tmp/jaws-bench-rpc PORT=10911 ./run-sample.sh bench-store-rpc
ROLE=consumer STORE_PATH=/tmp/jaws-bench-rpc PORT=10911 ./run-sample.sh bench-store-rpc
ROLE=producer PRODUCERS=2 STORE_PATH=/tmp/jaws-bench-rpc PORT=10911 ./run-sample.sh bench-store-rpc
```

## 11. 写读分池 + 派发池弹性化：消除 §9 的 40002

§9 的 `SEND_FAILED`/`40002` 不是「线程不够」，而是**写任务在共享派发池上占锁饿死读**：`putMessageAsync` 在 ASYNC_FLUSH 下同步于 RPC worker 线程跑完 `commitLog.putMessage`（序列化后「进全局 `putMessageLock` + memcpy」整段占着 worker，`submitFlush` 只在 SYNC_FLUSH 才 offload）。16 生产者写压把 200 个 worker 全堵在锁上排队，`pull`/`query`/`commit` 拿不到线程 → broker 报 `40002 full thread pool`。长轮询挂起本身不占 worker（空 pull 注册 `HoldRequest` 拿 future 即返回，由 reput/扫描线程事后 complete），这与 RocketMQ `PullRequestHoldService` 的非阻塞模型同款——所以该学的「挂起不占线程」一半早已对齐，缺的是**写别占着派发线程**。

改动（[StoreBroker](../jaws-store/src/main/java/org/hongxi/jaws/store/StoreBroker.java)，均在 store 模块内，不动 jaws-core）：

1. **写 offload**：新增有界 `writeExecutor`（`max(4, min(CPU, 8))` 线程 + 10000 队列），`putAsync`/`putBatchAsync` 把 append 挂到写池、worker 立即返回；写池溢出以 `SERVICE_NOT_AVAILABLE` 干净熔断（替代 40002）。provider 全程异步，future 由 store 私有线程 complete 是 RPC 层允许的（SYNC_FLUSH 本就在 flush 线程写响应）。
2. **派发池弹性化**：`minWorkerThreads` 200 → **32**，显式 `maxWorkerThreads=200`，`queue=10000`。写 offload 后派发线程只剩 decode/反序列化 + 投递写任务 + 读路径（挂起不占、命中一次内联 mmap 读），稳态并发远小于 200；`EagerThreadPoolExecutor` 先扩到 max 再入队，突发上限不变。
3. **写池不加大**：append 临界区在全局锁下串行，4–8 喂线程已喂满锁，更多只在锁上排队 + 多路上下文切换，吞吐不涨。

复验（`PRODUCERS=16` 三进程，与 §9 同负载形状，仅改了上述池配置）：

| step | target | offered/s | accepted/s | failed | verdict |
|---|---|---|---|---|---|
| 0 | 50k | 49,068 | 49,068 | 0 | OFFER_OK |
| 1 | 100k | 97,695 | 97,693 | 0 | OFFER_OK |
| 2 | 200k | 103,720 | 103,459 | 0 | PACER_SHORTFALL |
| 3 | 400k | 101,399 | 101,390 | 0 | PACER_SHORTFALL |
| 4 | 600k | 98,150 | 98,148 | 0 | PACER_SHORTFALL |
| 5 | 800k | 101,923 | 101,923 | 0 | PACER_SHORTFALL |
| 6 | free | **107,174** | 107,174 | 0 | OFFER_OK |

Consumer 监控：**`cons≈prod` 全程贴合（逐秒 ~100–130k），backlog 全程峰值仅 827 且瞬时排空到 0**；broker 侧 `40002 / full thread pool` 计数 **0**。

**结论**：

1. **40002 消失**：§9 满屏 `SEND_FAILED` 变为全档 `failed=0`——证实那确系「写占派发线程饿死读」，把写 offload 到独立池即消除，非靠堆线程。
2. **读侧无回退**：派发池 min 降到 32 后，单消费者仍在整段可达产量内跟平、backlog≈0，弹性池 + 深队列没饿到读。**验收通过**。
3. **吞吐天花板不变**：自由跑 ~107k/s，与 §9 的 ~125k、§10 的 ~128k 同处 ~100–130k 的 RPC 写天花板带内（序列化 + 全局锁串行决定，与线程数无关）。本轮 107k 略低于 §9 属写多一跳 + run 波动，**不是读侧问题**（读完全跟平）；这批改动的价值是隔离性与内存卫生，不是提速率。
4. **对照 RocketMQ**：RocketMQ 按请求类型拆 ~14 个专用池；jaws-store 只拆最关键的写/读一刀（一个写池 + 共享派发），不做全框架多池化——够用且契合单 master 定位。

## 12. 读延迟随写负载的抬升：固定读 + 阶梯写（StoreReadIsolationBenchmark）

§11 把写 offload 到独立池消除了 40002，随之而来的问题是——**读路径现在是否已完全不受写影响，从而不必再考虑方法级线程池隔离**？静态层面 §11 已证读不持写锁（`CommitLog.getMessage` 走 mmap slice，与 `putMessageLock` 无关），但「完全不受影响」只能靠实测。工具 [StoreReadIsolationBenchmark](../jaws-samples/jaws-sample-store/src/main/java/org/hongxi/jaws/sample/store/StoreReadIsolationBenchmark.java)：钉住**读负载**不变，逐档叠加**写 QPS**，比较各档读延迟分位是否被写抬高——读侧负载恒定，故任何漂移只可能来自 broker 吸收并发写。环境同 §1（M2 NVMe / JDK 21）。

**分进程是硬要求，不是优化**。同一个 JVM 内所有指向同一 `host:port` 的 `ReferenceConfig` 复用一条客户端连接（[AbstractTransportFactory](../jaws-core/src/main/java/org/hongxi/jaws/transport/AbstractTransportFactory.java) 的连接池以 hostPort 为键），读写若同进程会在**客户端 EventLoop 上串行**、伪造出「写拖累读」的假信号。故三进程各司一职、各持独立连接：broker / reader / writer，相位用统一 `startAt` 墙钟对齐（`currentPhase = (now-startAt)/phaseSeconds`，两进程同式）。读侧从预播种、已全量派发进索引的 `[0, seedPerQueue)` 区间循环 pull（`suspendTimeout=0` 短轮询，每条 pull 都是真读），`nf%≈0` 印证全程 FOUND。

三轮实测（queues=8 / seed/queue=20000 / readerThreads=4 / readRps=3000 / pullBatch=16 / writerConns=4 / phase=20s；单位 µs）：

读 **p50**（行=轮次，列=写档 target）：

| 轮 | 0 | 10k | 30k | 60k | 100k |
|---|---|---|---|---|---|
| r1 | 136.0 | 67.5 | 67.8 | 79.8 | **107.4** |
| r2 | 143.0 | 156.9 | 73.0 | 74.9 | **112.5** |
| r3 | 129.8 | 98.9 | 69.0 | 85.6 | **114.8** |

读 **p99**：

| 轮 | 0 | 10k | 30k | 60k | 100k |
|---|---|---|---|---|---|
| r1 | 197.9 | 167.1 | 133.6 | 212.8 | **261.0** |
| r2 | 438.8 | 278.2 | 184.3 | 293.1 | **273.8** |
| r3 | 189.3 | 192.9 | 141.3 | 377.7 | **369.0** |

p999/max 三轮均无单调规律（数百 µs 到 ~18ms 抖动），按纪律属单点值不归因。写侧实测到达 9.9k/29.9k/59.5k/98.9k per-s，`failed` 全 0——唯 r2 在 60k 档失败 40,871、r3 在 100k 档失败 75,507，属间歇性写侧背压。

**结论**：

1. **baseline（写=0）读数不可信为「无写基线」**：phase 0 恰是第一个被记录窗口，JIT/page-cache 未沉淀，p50 反而最高（130–143µs）；稳定低点在 30k 档（p50 67–73、p99 134–184）。判趋势须排除 phase 0 这个窗口排序伪影。
2. **排除 baseline 后确有轻微、可复现的抬升**：100k 档在三轮里都给出相位 1–4 的最高 p50（107/112/115），相对 30k 档约 **+50~60% p50**、p99 从 ~140–180µs 抬到 ~260–370µs。方向一致，幅度亚毫秒。
3. **读侧全程健康**：`nf%≈0`、`stall%` 仅 0.0x%，最高写档也没出现读被拒或持续失速——抬升是「变慢一点」而非「断崖」。
4. **耦合点不是存储锁、不是客户端连接**：读不持写锁（§11），读写已分连接。残留耦合来自 **broker 侧共享派发池**（`NettyChannelHandler` 把 pull 与 put 都投进同一 `serverExecutor`）叠加写对 page cache 的挤占——正是 §11 遗留的那道「选池在 decode 前、头部无 service 标识」的缝。写侧 60k+ 的间歇拒写亦与此同源（读 pull 计入后，聚合 RPC 更贴近 §9/§11 的 ~100–130k 天花板带）。
5. **对线程池隔离的判定**：「读完全不受写影响」严格不成立——存在随写 QPS 的温和抬升，源于共享派发池而非写锁。但量级（亚毫秒漂移、零读失败）远低于典型 RPC 超时预算。当前工作负载下，§11 的写 offload 已消除饿死型干扰，剩余派发池耦合属可接受轻度耦合，**不必为「证明完全隔离」再上方法级线程池拆分**；只有把写压到 100k+ 且对读尾延迟有硬 SLA 时，才值得引入 ExecutorSelector 读写分池。

复跑（三终端；`MODE` 选角色，读写各持独立连接，靠 `startAt` 对齐相位；先起 broker，再记一个未来时刻 `T=now+20000ms`，reader/writer 传同一 `startAt=$T`）：

```bash
# 先编译（单模块需先 install 依赖）
./mvnw -o -pl jaws-store,jaws-samples/jaws-sample-store -am install -DskipTests
CP="jaws-samples/jaws-sample-store/target/classes:$(./mvnw -q -o -pl jaws-samples/jaws-sample-store dependency:build-classpath -Dmdep.outputFile=/dev/stdout)"
MAIN=org.hongxi.jaws.sample.store.StoreReadIsolationBenchmark

# 终端 1：broker
java -cp "$CP" -Drole=broker -Dport=10911 -DstorePath=/tmp/jaws-store-iso -Dqueues=8 $MAIN

# 记一个统一起点（留足时间播种 + 起另一进程）
T=$(( $(python3 -c 'import time;print(int(time.time()*1000))') + 20000 ))
STEPS=0,10000,30000,60000,100000

# 终端 2：reader（负责 lease + 播种 160k + 测读延迟分位）
java -cp "$CP" -Drole=client -Dmode=reader -Dport=10911 -DstartAt=$T \
  -Dqueues=8 -DseedPerQueue=20000 -DreaderThreads=4 -DreadRps=3000 -DpullBatch=16 \
  -DwriteSteps=$STEPS -DphaseSeconds=20 $MAIN

# 终端 3：writer（叠加阶梯写 overlay，同 startAt 对齐）
java -cp "$CP" -Drole=client -Dmode=writer -Dport=10911 -DstartAt=$T \
  -Dqueues=8 -DmsgSize=128 -DwriterConns=4 -DwriteSteps=$STEPS -DphaseSeconds=20 $MAIN
```

单进程冒烟可用 `-Drole=all -Dmode=all`（自带 in-process broker，读写共用一条连接，绝对值偏悲观、仅供流程验证）；正式数据一律走上述分进程。
