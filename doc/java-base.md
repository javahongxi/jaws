# Java Base Five Topics：Jaws 作者的 java.base 精读计划

> 本文记录一个务实的决定：精力有限的情况下，`java.base` 里只精读五个主题。选择标准不是"经典"，而是**它们撑起了 Jaws 框架里每一个并发行为**——存（容器）、等（同步器）、跑（线程池）。每个主题附阅读重点、Jaws 对应代码、读完后必须能回答的验收问题。

## 0. 为什么是这五个

Jaws 全仓（含 samples）的 `java.util` 使用密度统计：

```
ArrayList             107 处    万物基底
ConcurrentHashMap      59 处    跨线程共享状态（本文主题 ②）
HashMap                59 处    本地缓存 / attachment（本文主题 ①）
CopyOnWriteArrayList   31 处    读极多写极少的监听器 / invoker 列表
ScheduledExecutor      N 处     Harbor 推送引擎、看门狗、重连（本文主题 ⑤）
ThreadPoolExecutor     各服务    serverExecutor 业务线程池（本文主题 ④）
CountDownLatch         示例      流式回调等待（本文主题 ③）
```

五个主题的依赖关系即推荐阅读顺序，**不要乱序**：

```
① HashMap ──桶分裂/树化知识──→ ② ConcurrentHashMap
                                      │
③ AQS + CountDownLatch + ReentrantLock ──Condition/锁──→ ④ ThreadPoolExecutor
                                                              │
                                              ⑤ ScheduledThreadPoolExecutor（终点站）
```

被裁掉的主题及理由：TreeMap（Jaws 仅哈希环 1 处消费者，降级为按需查）、DelayQueue（并入 ⑤，DelayedWorkQueue 就是它的演化版）、Semaphore（与 CountDownLatch 同为共享模式，30 分钟速览即可）。

---

## ① HashMap —— 地基（已完成大半）

**源码**：`java.base/java/util/HashMap.java`

### 关键结论（已逐条对照 JDK 21 源码验证）

1. **扰动函数** `hash = h ^ (h >>> 16)`：桶下标 `hash & (n-1)` 只有低位参与，扰动把高位信息送进低位。
2. **容量恒为 2 的幂**（`tableSizeFor`）：位运算取模 + 扩容高低位分裂的前提。
3. **resize 高低位分裂**：容量翻倍后元素新位置只有两种可能，看 `hash & oldCap`——0 留原位，1 移到 `原位 + oldCap`。一趟 O(n) 完成，无需 rehash。
4. **树化是逐桶决策**：链长 ≥ 8 且 `table.length ≥ 64` 才 `treeifyBin`；容量不足时优先扩容。树桶内部仍维持双向链表（`next/prev`），树只服务 O(log n) 查找，迭代走链表。
5. **继承链**（易错点）：`TreeNode extends LinkedHashMap.Entry extends HashMap.Node`。挂到 LinkedHashMap.Entry 下是为了 LinkedHashMap 桶树化后仍能维护 `before/after` 的 LRU 顺序，HashMap 与 TreeMap 之间**没有任何代码复用**。
6. **混合异构**：同一张表里链桶、树桶、空桶共存；resize 时一棵树可能分裂成两棵树、两条链或一树一链（节点 ≤ 6 退化回链，滞回设计防抖动）。

### Jaws 对应

- `ConsistentHashLoadBalance.hash()` 的 FNV1a：解决的是**值空间分布**问题——`String.hashCode` 对相似短串（`arg-0`..`arg-999`）产出的值聚簇在窄区间，整批请求落进哈希环同一段弧，实测 99% 流量倾斜到单节点。注意这条链路上没有 HashMap（环是 TreeMap，靠比较器不靠哈希桶），别和上面第 1 条的扰动函数混为一谈：扰动解决的是**索引位选取**（低位丢弃高位）导致的桶碰撞，两者机理不同、解法不通用（聚簇值扰动后依然聚簇）。对照记忆：同一个 hashCode 缺陷，在 HashMap 里表现为碰撞，在哈希环里表现为弧长不均。
- 配置类保序输出用 `LinkedHashMap`（11 处）：遍历顺序有语义时必须换有序 Map，HashMap 的遍历顺序依赖桶物理布局，扩容后即变。

### 验收问题

- [ ] 为什么 `HashMap` 的 `get` 先比 hash 再比 `==`/`equals`？自定义 key 只写 equals 不写 hashCode 会怎样？
- [ ] 负载因子 0.75、树化阈值 8、退树阈值 6、树化最小容量 64，各自的设计依据？
- [ ] 1.7 头插并发扩容成环的机理，1.8 尾插为什么消灭了环但仍线程不安全？

---

## ② ConcurrentHashMap —— 项目命脉（59 处）

**源码**：`java.base/java/util/concurrent/ConcurrentHashMap.java`

### 版本演进主线

```
JDK 5~7   Segment 分段锁：Segment extends ReentrantLock，并发度构造期固定（默认 16）
JDK 8+    Node 数组 + CAS + synchronized 锁单桶：
          · 锁粒度从"段"到"桶"
          · 空桶插入走 CAS，完全无锁
          · sizeCtl 状态机 + transferIndex 切片 → helpTransfer 多线程协助扩容
          · baseCount + CounterCell[] 分段计数（LongAdder 同款）
          · 数组元素访问统一走 tabAt/setTabAt（VarHandle getOpaque/setVolatile），
            因为 Java 没有 volatile 元素数组
```

JDK 8 敢用 `synchronized` 的原因：锁的是单条桶的头节点，冲突概率极低、临界区极短，且锁对象就是节点自己，比每段一个 ReentrantLock 省内存。

### Jaws 对应

| 代码 | 机制 |
|---|---|
| `AbstractClient.callbackMap` / `timeoutMap`（全链路异步的 requestId→Future 关联中枢） | 收响应的 IO 线程、超时任务的定时器线程、`close()` 清理线程三方竞争同一个 key，`removeCallback` 里的 `remove(requestId)` **返回旧值的原子性**就是"胜者独占完成权"的仲裁（NettyClient 源码注释原话：atomically claim）。get 后再 remove 的复合写法会让响应和超时双方都以为自己是胜者、双重完成 future |
| `AbstractClient.registerCallback` 的 `callbackMap.size() >= MAX_INFLIGHT_REQUESTS` 拒绝 | size() 是 baseCount + CounterCell[] 条带求和（LongAdder 同款）的弱一致快照——当防 OOM 背压的软阈值没问题，当精确计数用就会错 |
| `AbstractRequestHandler.providers`（服务端请求路径正中的服务分发表） | 每次 RPC 分发都无锁读一次，写入只发生在生命周期线程——写一次读多次，CHM 四种使用形态里最极端的只读形态，代码注释直接写明 "requests read providers lock-free on every RPC" |
| `ExtensionLoader.singletonInstances.computeIfAbsent`（SPI 单例缓存） | mappingFunction 内再访问同一 key 会递归锁死（JDK 9 起抛 IllegalStateException）——读 transfer 源码理解 bin 锁语义后自明 |
| `FailbackRegistry.failedSubscribed.computeIfAbsent(url, k -> newKeySet()).add(listener)` | 只有"建内层 Set"是原子的，`.add` 发生在锁外；正确性靠 add 自身原子 + 重试线程弱一致迭代收敛。注意 computeIfAbsent 保护的是缺失值创建，不是整段读-改-写 |

### 验收问题

- [ ] `sizeCtl` 在初始化、扩容、计数三个角色间如何复用（位压缩）？
- [ ] `helpTransfer` 的线程如何安全地加入迁移？ForwardingNode 起什么作用？
- [ ] `put` 为什么不能接受 null value，而 `get` 返回 null 时你必须用什么 API 区分"不存在"和"值为 null"？
- [ ] `computeIfAbsent` 什么时候锁整桶、什么时候完全不锁？

---

## ③ AQS + CountDownLatch + ReentrantLock —— 一切"等待"的地基

**源码**：`AbstractQueuedSynchronizer.java`、`CountDownLatch.java`、`ReentrantLock.java`、`Condition.java`

### 为什么这三件一组

AQS 四块拼图，用三个类刚好覆盖：

```
独占模式（exclusive）  →  ReentrantLock（Semaphore/Latch 都不走这条路）
共享模式（shared）     →  CountDownLatch（Semaphore 是其变体，差一个公平性检查）
state 的多义性         →  Latch=剩余次数 / Lock=重入深度，读两个才算真懂
Condition 等待队列      →  只有 ReentrantLock 暴露 newCondition；
                          ConditionObject 的 transferForSignal 是全站最难也最值钱的一段
```

### 内部阅读次序

1. ReentrantLock 非公平锁路径（最少代码走通独占 + 重入 + state 三义）
2. CountDownLatch（共享模式 `tryAcquireShared` 的向上传播）
3. ConditionObject（条件队列 ↔ AQS 同步队列的 transfer 协议、`hasWaiters` 必须持锁检查）
4. ~~读写锁~~ 跳过（AQS 应用的重复练习）；Semaphore 30 分钟速览

### Jaws 对应（Condition 是 ④⑤ 的前置知识）

- sample 里流式调用的 `CountDownLatch` + `onCompleted`/`await` 模式——忘写 `await()` 流式结果直接丢失（Dubbo Triple 示例的经典错误，Jaws sample 同款结构）。
- ④ 的 `ExecutorQueue extends LinkedBlockingQueue`：内部 `putLock/takeLock` 两把 ReentrantLock + `notEmpty/notFull` 两个 Condition，"为什么 LBQ 两把锁而 ABQ 一把"要到这站找答案。
- ⑤ 的 DelayedWorkQueue：`available = lock.newCondition()`，take 线程 `awaitNanos` 挂起全靠本站机制。
- 番外：Netty `SingleThreadEventExecutor` 的 waker 是同款 ReentrantLock + Condition 模型。

### 验收问题

- [ ] CLH 变体队列里 SIGNAL/PROPAGATE 状态的传播链；`tryAcquireShared` 返回负值意味着什么？
- [ ] 公平与非公平 ReentrantLock 的 `tryAcquire` 差别在哪一行？
- [ ] `signal()` 之后等待线程去了哪里？为什么叫 transfer 而不是唤醒？
- [ ] AQS 为什么用模板方法而非组合？`getState/setState` 的"语义留给子类"设计利弊？

---

## ④ ThreadPoolExecutor —— 有自定义实现，这站是"对答案"

**源码**：`ThreadPoolExecutor.java`、`LinkedBlockingQueue.java`

### 重点

```
execute 的 ctl 编码        高 3 位运行状态（RUNNING/SHUTDOWN/.../TIDYING/TERMINATED）
                          + 低 29 位 workerCount，一个 long/AtomicInteger 搞定
runWorker 的 getTask 循环  core 用 take()、non-core 用 poll(keepAlive)——
                          线程回收的本质是这条超时 poll
Worker 继承 AQS            不可重入锁语义区分"中断空闲 worker"与"中断执行中任务"
异常吞噬                   submit → FutureTask 保存异常；execute → 走
                          UncaughtExceptionHandler，runWorker catch 后不 rethrow
```

### Jaws 对应：EagerThreadPoolExecutor + ExecutorQueue

这是本站的独特优势——**你反着改造过 TPE，读源码是验证自己的每个 hack**：

- 标准 TPE"先入队后扩线程"；Eager 策略反转为"先扩到 max 再入队"，靠重写 `offer` 实现（`submittedTasksCount <= poolSize` 判定 + 第二次 offer 入真队列）。读 `execute` 源码才能确认为什么必须借 `ctl` 的 RUNNING 检查完成原子性，以及 `workerQueueSize=0` 时 `maxSubmittedTasks = maximumPoolSize + 0` 导致探针任务被直接拒绝的边界（实测踩过，配 `workerQueueSize=1` 修复）。
- benchmark 时池稳定在 corePoolSize=20：`submittedTasksCount ≤ poolSize` 使任务被空闲线程直接接走，永不触发扩容——读懂 getTask/wakeup 后才明白这个"不扩"是设计而非失效。
- `prestartAllCoreThreads()` 与拒绝策略里访问 `getActiveCount()`（这也是 NettyServer/Http2Server 字段类型统一为 `ThreadPoolExecutor` 而非 `ExecutorService` 的原因）。

### 验收问题

- [ ] 五种状态迁移图；shutdownNow 与 shutdown 在 `interruptIdleWorkers` 上的区别？
- [ ] 为什么 `Worker` 锁不能重入？`shutdown` 时如何避免中断正在执行任务的线程？
- [ ] `allowCoreThreadTimeOut` 打开后 core/non-core 的 getTask 路径如何统一？
- [ ] 四种拒绝策略 + CallerRuns 反压原理；Eager 的自定义拒绝策略为什么需要 `getActiveCount()`？

---

## ⑤ ScheduledThreadPoolExecutor —— 终点站：Harbor 推送引擎的真相

**源码**：`ScheduledThreadPoolExecutor.java`（内部类 `ScheduledFutureTask`、`DelayedWorkQueue`）

### 重点

```
DelayedWorkQueue    手写最小堆（siftUp/siftDown，Object[] 直接存，无节点对象分配）
                    与 DelayQueue 的关系：同一问题（取最近到期）的演化实现——
                    LBQ 的 put/take Condition 换成了 available + q.size() 双检 +
                    "队首变化才 signal" 的 leader 式优化
ScheduledFutureTask state: WAITING → PROPAGATE → RUNNING → CANCELLED
                    period 字段：≤0 一次性 / >0 fixedRate / <0 fixedDelay
setNextTime         两种周期的 drift 处理差异（fixedDelay 基于完成时间重排，
                    fixedRate 基于上次计划时间追赶）
池参数藏在构造器  所有 STE 构造器固定传 super(corePoolSize, MAX_VALUE, 10ms)——
                    maximumPoolSize 无意义（源码注释：core 与 max "effectively
                    identical"），多余线程闲置 10ms 即回收；javadoc 同时警告别把
                    core 设 0 或开 allowCoreThreadTimeOut，否则任务到期无人取
removeOnCancel    cancel() 默认只置 CANCEL 标志、节点留在堆里直到到期被 poll 剔除
    （默认 false）      （O(1) 标记 + 摊销清理）；setRemoveOnCancelPolicy(true) 改为
                    O(log n) 立即堆删除——高频取消场景防 cancelled-entry 垃圾堆积
异常 = 静默停摆     周期任务抛一次异常 → runWorker catch → FutureTask.setException
                    → 该任务永远不再进堆，且没有任何日志
```

### Jaws 对应

- `PushDelayTaskEngine`：`scheduler.schedule(key, quietPeriod)` + `Set<ServiceKey>` 合并推送。读完本站能回答两个既有决策的正确性：为什么合并语义必须 Set 不能用队列（DelayQueue 会重复入队多次推送）；为什么 `scheduleQuietly` 在 scheduler 关闭时要原子 add+remove 回滚防死条目。
- 真正基于 STE 的调度全家：Harbor `HealthCheckScheduler`、`DistroProtocol`、`FailbackRegistry.retryExecutor`、Wire 的 `KEEPALIVE_SCHEDULER`/`RETRY_SCHEDULER`/`LIFECYCLE_SCHEDULER`（GOAWAY 后重连）、`ReferenceDestroyer`。
- 对照组——**不靠调度器**的两类：`NettyClient` 的 send-reconnect（`send.reconnect` 默认 true）是请求路径内的 lazy 重连：发现 `!isAvailable()` 时当场 `resetErrorCount() + open()`，零后台线程；心跳则是 Netty `IdleStateHandler` 在 EventLoop 上的 `schedule`，也不是 STE。读本站时对比三种定时机制（STE 堆 / EventLoop 定时任务 / 请求路径惰性检查）的适用边界。
- 消费端超时全家已统一为 STE：`AbstractClient.timeoutTimer` 是 `ScheduledThreadPoolExecutor(1, daemon)`，每个请求注册时挂一个 one-shot 超时任务（`registerCallback`/`removeCallback` + `timeoutMap`）。这段迁移本身就站在本站肩膀上：弃用 HashedWheelTimer 的原因是桶链表上 `Timeout.remove()` 实测负载下吃约 10% CPU，而 `removeOnCancelPolicy=true` 让取消变成 O(log n) 堆删除、不留 cancelled-entry 垃圾——读 DelayedWorkQueue 后才能判断这笔账（时间轮取消 O(1) vs 堆取消 O(log n)，为什么高基数短延时场景反而是堆赢）。
- 时间轮 vs 最小堆的调度哲学对比已随统一实现收敛：消费链路上剩下的定时需求全部由 STE 或 EventLoop 承担（见上两条）。
- `scheduleWithFixedDelay` 包 try-catch 是**正确防御**：不包的话异常导致周期任务静默停摆（见重点块"异常 = 静默停摆"条），Harbor 里曾出现看门狗误删连接的排查困难正源于此类静默。

### 验收问题

- [ ] `schedule()` 的入队为什么需要 `q.offer` 后检查"是否成为新队首"再 signal available？
- [ ] fixedRate 的"追赶"在任务超时一轮后如何补跑、如何放弃？
- [ ] 周期任务异常后 FutureTask 状态如何变化？为什么 set 了异常就再也取不到任务？
- [ ] DelayedWorkQueue 扩容时机与堆"空洞"清理（siftDown 时的移除）如何实现？
- [ ] `removeOnCancelPolicy` 默认 false 的取舍是什么（O(1) 标记 vs O(log n) 删除、谁付摊销）？AbstractClient 的一请求一任务模型为什么必须开？
- [ ] corePoolSize=1 的 timeoutTimer 扛万级 QPS 的一次注册一次取消，瓶颈会先出现在哪里？

---

## 时间配比与进度

```
① HashMap                     10%   复习查漏（本文档 ① 节即学习笔记）    [√]
② ConcurrentHashMap           25%   sizeCtl / helpTransfer 状态机         [ ]
③ AQS + Latch + ReentrantLock 30%   辐射面最大，ConditionObject 是硬骨头   [ ]
④ ThreadPoolExecutor          20%   对照 EagerThreadPoolExecutor 读        [ ]
⑤ ScheduledThreadPoolExecutor 15%   收尾，串起 ③④                         [ ]
```

每站读完立即回项目做一次"源码考古笔记"：把与 Jaws 自定义实现（EagerThreadPoolExecutor、ExecutorQueue、PushDelayTaskEngine）的差异点补进对应类的英文注释，知识焊死在代码里。

## 附录：本计划依赖的已验证事实清单

以下结论均已对照本机 JDK 21（temurin-21.0.11）`src.zip` 源码逐条验证，非二手资料：

1. `TreeNode extends LinkedHashMap.Entry extends Node`，与 TreeMap 无代码复用（HashMap.java L1966 / LinkedHashMap.java L205）。
2. CHM 锁机制在 JDK 8 从 Segment 切换到"锁头节点 + CAS 空桶插入"。
3. HashMap resize 高低位分裂：`hash & oldCap` 决定留原位或移 `原位 + oldCap`。
4. 树化双条件：链长 ≥ 8 且容量 ≥ 64，容量不足时扩容优先。
5. `LinkedBlockingQueue` 双锁（putLock/takeLock）结构——④ 的前置事实。
6. CHM 遍历顺序依赖桶物理布局 → 无序集合上禁止用顺序依赖运算（滚动哈希）计算 revision，用 XOR/求和等交换律运算——Jaws 实测 bug 与修复。
7. `java.util.ImmutableCollections`（JDK 9 起，List.of/Set.of/Map.of 的实现，List12/ListN/SetN/Map1/MapN + CollSer 序列化代理）：真不可变 vs `Collections.unmodifiableXxx` 包装视图的区别；拒绝 null 与 CHM/Optional 同一设计立场。跨线程发布的只读配置优先用 `X.of`。
8. `ArrayDeque`：环形数组 + 位运算取模，JDK 基础组件 23 处使用（Resolver/URLClassPath/SelectorImpl/ZipFile），Jaws 暂 0 处——协议解析/调度缓冲场景（HEADERS-DATA 帧排序、CONTINUATION 聚合）的候选优化项。
