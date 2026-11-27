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
