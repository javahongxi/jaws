# Java Base Five Topics：Jaws 作者的 java.base 精读计划

> 本文记录一个务实的决定：精力有限的情况下，`java.base` 里只精读五个主题。选择标准不是"经典"，而是**它们撑起了 Jaws 框架里每一个并发行为**——存（容器）、等（同步器）、跑（线程池）。每个主题附阅读重点、Jaws 对应代码、读完后必须能回答的验收问题。
>
> 定性：个人学习笔记，非框架文档。

## 0. 为什么是这五个

Jaws 全仓（含 samples，main+test）的使用密度统计。口径：import 该类的文件数，
2026-09-27 统计——仓库持续演进，重跑即漂移，只看量级不看个位：

```
ArrayList             102 文件    万物基底
ConcurrentHashMap      57 文件    跨线程共享状态（本文主题 ②）
HashMap                61 文件    本地缓存 / attachment（本文主题 ①）
CopyOnWriteArrayList   31 文件    读极多写极少的监听器 / invoker 列表
ScheduledExecutor*     13 文件    Harbor 推送引擎、看门狗、重连（本文主题 ⑤）
ThreadPoolExecutor      3 文件    serverExecutor 业务线程池（本文主题 ④）
CountDownLatch         23 文件    流式回调等待（本文主题 ③）
```

\* 按 `ScheduledExecutorService` import 计；实现一律是 `new ScheduledThreadPoolExecutor(...)`。

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
2. **表长（`table.length`）恒为 2 的幂**（`tableSizeFor`）：位运算取模 + 扩容高低位分裂的前提。
3. **resize 触发看 `size`（HashMap 大小 = 元素个数），不是 `table.length`**：`putVal` 插入末尾 `if (++size > threshold) resize()`（L668），`threshold = 容量 × 负载因子`——常规扩容以"装了多少元素"为准（与第 4 条树化门槛比 `table.length` 是两回事）。**高低位分裂**：表长翻倍后元素新位置只有两种可能，看 `hash & oldCap`——0 留原位，1 移到 `原位 + oldCap`。一趟 O(n) 完成，无需 rehash。
4. **树化是逐桶决策**：链长 ≥ 8 且 `table.length ≥ 64` 才 `treeifyBin`；表长不足时优先扩容（`treeifyBin` 首行就 `if (tab == null || (n = tab.length) < MIN_TREEIFY_CAPACITY) resize();`，L761-764）。树桶内部仍维持双向链表（`next` + `TreeNode.prev`，后者注释原话 "needed to unlink next upon deletion"），查找走 O(log n) 树，迭代走链表（`HashIterator` 只跟 `next`，L1581-1605）。
5. **继承链**（易错点）：`TreeNode extends LinkedHashMap.Entry extends HashMap.Node`（L1966 / L205）。挂到 LinkedHashMap.Entry 下是为了 LinkedHashMap 桶树化后仍能维护 `before/after` 的 LRU 顺序，源码 javadoc 原话只说了一句更宽的："Extends LinkedHashMap.Entry (which in turn extends Node) so can be used as extension of either regular or linked node"（L1962-1965）。HashMap 与 TreeMap 之间**没有任何代码复用**。
6. **混合异构**：同一张表里链桶、树桶、空桶共存；resize 时一棵树可能分裂成两棵树、两条链或一树一链（节点 ≤ 6 退化回链，滞回设计防抖动）。
7. **四个数字都不是拍脑袋**（类注释 L176-200 自带推导）：`TreeNode` 约是普通节点两倍大，所以上树要"足够多节点"才划算；理想随机 hash 下桶长服从参数 λ≈0.5 的 Poisson 分布（λ 来自 0.75 默认负载因子），源码直接列了期望频次表：`0: 0.60653066 … 6: 0.00001316、7: 0.00000094、8: 0.00000006、more: less than 1 in ten million`——**默认下一个桶长到 8 的概率约六百万分之一**。所以树化是**止损机制而非优化**：真走到这条分支，意味着 hash 质量已崩（相似短串聚簇，或故意碰撞），此时 O(log n) 只是把 O(n) 链查找的坑兜住。UNTREEIFY=6 与 8 之间留 gap 是滞回，避免在阈值附近反复转换；`MIN_TREEIFY_CAPACITY=64`（= 4×8）防止"表太小导致的碰撞"被误判成"该树化"——`table.length` 不足时永远先扩容。

### Jaws 对应

- `ConsistentHashLoadBalance.hash()` 的 FNV1a：解决的是**值空间分布**问题——`String.hashCode` 对相似短串（`arg-0`..`arg-999`）产出的值聚簇在窄区间，整批请求落进哈希环同一段弧，实测 99% 流量倾斜到单节点。注意这条链路上没有 HashMap（环是 TreeMap，靠比较器不靠哈希桶），别和上面第 1 条的扰动函数混为一谈：扰动解决的是**索引位选取**（低位丢弃高位）导致的桶碰撞，两者机理不同、解法不通用（聚簇值扰动后依然聚簇）。对照记忆：同一个 hashCode 缺陷，在 HashMap 里表现为碰撞（上一节第 7 条：严重到触发树化就是它的信号），在哈希环里表现为弧长不均。
- 配置类保序输出用 `LinkedHashMap`（11 处）：遍历顺序有语义时必须换有序 Map，HashMap 的遍历顺序依赖桶物理布局，扩容后即变。

### 验收问题

- [ ] 为什么 `HashMap` 的 `get` 先比 hash 再比 `==`/`equals`？自定义 key 只写 equals 不写 hashCode 会怎样？
- [ ] 负载因子 0.75、树化阈值 8、退树阈值 6、树化最小表长 64（`MIN_TREEIFY_CAPACITY`，比的是 `table.length`），各自的设计依据？（第 7 条给了源码推导，能不看文档复述 Poisson 表最末两行才算过）
- [ ] 1.7 头插并发扩容成环的机理，1.8 尾插为什么消灭了环但仍线程不安全？

---

## ② ConcurrentHashMap —— 项目命脉（57 文件 import / 59 文件出现）

**源码**：`java.base/java/util/concurrent/ConcurrentHashMap.java`

### 版本演进主线

```
JDK 5~7   Segment 分段锁：Segment extends ReentrantLock，并发度构造期固定（默认 16）
          （JDK 21 里 DEFAULT_CONCURRENCY_LEVEL = 16 仍在，字段注释原话
           "Unused but defined for compatibility"，L523-526——纯兼容残留）
JDK 8+    Node 数组 + CAS + synchronized 锁单桶：
          · 锁粒度从"段"到"桶"
          · put 遇空桶走 casTabAt，完全无锁；computeIfAbsent 遇空桶先 CAS 一个
            ReservationNode 占位，再在这个自造对象上跑 mappingFunction
          · sizeCtl 状态机 + transferIndex 切片 → helpTransfer 多线程协助扩容
          · baseCount + CounterCell[] 分段计数（LongAdder 同款）
          · 数组元素访问统一走 tabAt/setTabAt（Unsafe reference 系直读数组：
            读 getReferenceAcquire / 写 putReferenceRelease，旁注原话"setTabAt
            always occur within locked regions, and so require only release
            ordering"，L754-755），因为 Java 没有 volatile 元素数组
          · 树桶的桶头不是普通节点而是 TreeBin（hash = TREEBIN），锁形态为
            synchronized(TreeBin) + lockState（WRITER/WAITER/READER，L2776-2780）
```

JDK 8 敢用 `synchronized` 的原因：锁的是单条桶的头节点，冲突概率极低、临界区极短，且锁对象就是节点自己，比每段一个 ReentrantLock 省内存。

### Jaws 对应

| 代码 | 机制 |
|---|---|
| `AbstractClient.callbackMap` / `timeoutMap`（全链路异步的 requestId→Future 关联中枢） | 收响应的 IO 线程、超时任务的定时器线程、`close()` 清理线程三方竞争同一个 key，`removeCallback` 里的 `remove(requestId)` **返回旧值的原子性**就是"胜者独占完成权"的仲裁（NettyClient 源码注释原话：atomically claim）。get 后再 remove 的复合写法会让响应和超时双方都以为自己是胜者、双重完成 future |
| `AbstractClient.registerCallback` 的 `callbackMap.size() >= MAX_INFLIGHT_REQUESTS` 拒绝 | size() 是 baseCount + CounterCell[] 条带求和（LongAdder 同款）的弱一致快照——当防 OOM 背压的软阈值没问题，当精确计数用就会错 |
| `AbstractRequestHandler.providers`（服务端请求路径正中的服务分发表） | 每次 RPC 分发都无锁读一次，写入只发生在生命周期线程——写一次读多次，CHM 四种使用形态里最极端的只读形态，代码注释直接写明 "requests read providers lock-free on every RPC" |
| `ExtensionLoader.singletonInstances.computeIfAbsent`（SPI 单例缓存） | 当前 mappingFunction 只做反射 `newInstance`，不碰同一个 map，所以安全；跨 type 取扩展走的是不同 loader 的不同 map。风险点：一旦把逻辑写成"在同 type 的 mappingFunction 里再取同 type 扩展"，JDK 9+ 会以 `IllegalStateException("Recursive update")` 检出（检测不到的情形仍可能死锁）——读 transfer 源码理解 bin 锁语义后自明 |
| `FailbackRegistry.failedSubscribed.computeIfAbsent(url, k -> newKeySet()).add(listener)` | 只有"建内层 Set"是原子的，`.add` 发生在锁外；正确性靠 add 自身原子 + 重试线程弱一致迭代收敛。注意 computeIfAbsent 保护的是缺失值创建，不是整段读-改-写 |

### 验收问题

- [ ] `sizeCtl` 的三态复用：未初始化时存什么、负数存什么（-1 / -(1+扩容线程数)，高 16 位 `resizeStamp` 起什么作用）、稳态存什么？为什么"元素计数"不在 sizeCtl 里？
- [ ] `helpTransfer` 的线程如何安全地加入迁移？ForwardingNode 起什么作用？（源码 L2363-2381：`rs = resizeStamp(n) << RESIZE_STAMP_SHIFT` 与 `sc == rs + MAX_RESIZERS / sc == rs + 1 / transferIndex <= 0` 三重退出条件）
- [ ] `put` 为什么不能接受 null value，而 `get` 返回 null 时你必须用什么 API 区分"不存在"和"值为 null"？
- [ ] `computeIfAbsent` 四条路径各自的加锁形态：什么时候完全不锁（命中桶头的 fast path）、什么时候锁自造的 ReservationNode、什么时候 `synchronized(f)` 锁桶头、什么时候根本不去锁而是 `helpTransfer`？"递归更新"靠什么检出（`pred.next != null` / 撞上 ReservationNode）？

---

## ③ AQS + CountDownLatch + ReentrantLock —— 一切"等待"的地基

**源码**：`AbstractQueuedSynchronizer.java`、`CountDownLatch.java`、`ReentrantLock.java`、`Condition.java`

### 为什么这三件一组

AQS 四块拼图，用三个类刚好覆盖：

```
独占模式（exclusive）  →  ReentrantLock（Semaphore/Latch 都不走这条路）
共享模式（shared）     →  CountDownLatch（与 Semaphore 同为共享模式：
                          Latch 一次性倒计数、无 release/不可重置；
                          Semaphore 计数可恢复，另分公平/非公平）
state 的多义性         →  Latch=剩余次数 / Lock=重入深度，读两个才算真懂
Condition 等待队列      →  只有 ReentrantLock 暴露 newCondition；
                          ConditionObject 的 doSignal→enqueue 迁移协议是全站最难也最值钱的一段
```

> **版本口径**：本机 JDK 17 与 21 的 AQS 均已是重写版（go-dark 之后的实现），`waitStatus` 的
> `SIGNAL/PROPAGATE`、`transferForSignal`、`setHeadAndPropagate` 都已消失——网上绝大多数 AQS
> 文章是 JDK 8~16 口径，读源码时先认清版本再对照。见文末清单第 9 条。

### 内部阅读次序

1. ReentrantLock 非公平锁路径（最少代码走通独占 + 重入 + state 三义；注意重入在 `initialTryLock` 而不在 `tryAcquire`）
2. CountDownLatch（共享模式的链式点名唤醒：`releaseShared → signalNext(head)`，加成功后的 `signalNextIfShared`）
3. ConditionObject（条件队列 ↔ AQS 同步队列的 transfer 协议、`getAndUnsetStatus(COND)` 的原子认领、`hasWaiters` 必须持锁检查）
4. ~~读写锁~~ 跳过（AQS 应用的重复练习）；Semaphore 30 分钟速览

### Jaws 对应（Condition 是 ④⑤ 的前置知识）

- sample 里流式调用的 `CountDownLatch` + `onCompleted`/`await` 模式——忘写 `await()` 流式结果直接丢失（Dubbo Triple 示例的经典错误，Jaws sample 同款结构）。
- ④ 的 `WorkQueue extends LinkedTransferQueue`：无锁 CAS 入队、无界，所以 `force()`（裸 `offer`）才永远成功——Eager"第二次 offer 入真队列"的地基在这站读懂。注意 `LinkedBlockingQueue` 的 `putLock/takeLock` 双锁 + `notEmpty/notFull` 双 Condition 是**另一条知识线**（"为什么 LBQ 两把锁而 ABQ 一把"），读 LBQ 源码本身时找答案；jaws 核心路径上没有 LBQ。
- ⑤ 的 DelayedWorkQueue：`available = lock.newCondition()`，take 线程 `awaitNanos` 挂起全靠本站机制。
- 番外：Netty `SingleThreadEventExecutor` 的 waker 是同款 ReentrantLock + Condition 模型。

### 现场笔记：RocketMQ 源码里的两个本站用户

**③-1 Semaphore 当"预算"用——一个化石层上的案例**（`DefaultMQProducerImpl` L121-123）

RocketMQ 带 timeout 的异步 `send` 三重载（L552/L1265/L1391）**全部 @Deprecated**（判词自 4.4 挂账：中间池把 timeout 语义拧成"含排队总预算"、异常 throws/callback 双通道打架；公开接口没标弃用只是委托进已弃用方法；5.x 现代路径 `request(msg, RequestCallback)` L1652 调用者线程直达 remoting，闸门对它不可达）。但这具化石里的 Semaphore 用法是三个正确姿势的标本：**许可与资源计量同构**（条数↔num、字节↔size 拆两枚 fair，非一枚混账）、**acquire 配时限**（`tryAcquire(timeout-costTime)`，L647-670，抢不到回 `RemotingTooMuchRequestException`）、**release 配回执旗**（成败布尔随 callback 带走按旗还账 L599-609，另有用 `release(±delta)` 在线扩缩额度的冷门招 L612-630）。教训两条：模式照学，但别据此给自家异步客户端加中间池——**中间池正是语义烂掉的根因**；jaws 的 `callbackMap.size()` 软阈值 fast-fail（`AbstractClient` L140-146，在途天然记账在 requestId→Future 中枢，无需第二结构）反而与 5.x 演进方向同构。

**③-2 LockSupport 的 sticky permit——ServiceThread 的"点名瞌睡"**（`common/.../ServiceThread.java` L105-142）

```java
public void wakeup() {
    if (hasNotified.get()) return;                       // 已约，不重复打扰
    if (hasNotified.compareAndSet(false, true)) {
        LockSupport.unpark(this.thread);                 // 信号写在 flag 上，也落在许可上
    }
}
protected void waitForRunning(long interval) {
    this.thread = Thread.currentThread();
    if (hasNotified.compareAndSet(true, false)) { onWaitEnd(); return; }   // 先查旗，压根不睡
    // LockSupport permits are sticky: an unpark delivered before park makes the next park
    // return at once, and the loop re-checks hasNotified, so no wakeup can be lost.        ← L123-124 原注释
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(interval);
    while (!hasNotified.get()) {                         // predicate loop 防的是"sticky 之外的 spurious return"
        ...
        LockSupport.parkNanos(this, remain);             // 睡满 deadline 为止，每圈补差
    }
    hasNotified.set(false);
    this.onWaitEnd();
}
```

这个场景是 **park 原语的主场、锁的错位**：一睡一叫、无共享临界区、不要求原子读走——用 ReentrantLock+Condition 的话，唤醒方必须持锁才能 signal，多付一套 AQS 排队/再抢锁的重型协议，还只是把"丢唤醒"从"signal 在 await 前到达"的坑挪到 predicate loop 的坑。LockSupport 的 sticky permit（每线程一个，unpark 先行则下个 park 直接返回）+ `hasNotified` CAS flag 双层，让"wakeup 先于 park 到达"天然不丢。铁证是演进本身：5.5.1 里两代实现并存——`remoting/common/ServiceThread.java` 还是老式 `volatile boolean + Object.wait/notify`（L32/L53-54），`common` 包这版已重写成上述结构并特意留注释解释 sticky。**Condition 何时才值得上**：需要多路等待、点名唤醒、带谓词队列时——本站 ③ 的 DelayedWorkQueue leader-follower 就是另一边（java-base-5 §二）。

**③-3 判据清单：什么等待拓扑必须上 ReentrantLock**

synchronized vs ReentrantLock 的选择点自 JDK 6 起不在性能（偏向锁 JDK 15 已废、锁消除两家用谁都一样），只在**表达能力**：synchronized 是语言原语，只能表达"获取—执行—必然释放"；ReentrantLock 把这三步拆成方法，拆开的每一步都是能力、也都是责任。决策程序五问，**任何一问答案是"要"，synchronized 出局**：

1. **限时抢锁？**（`tryLock(t, unit)`）例：NettyRemotingClient 的 channelTables 维护，`lockChannelTables.tryLock(3000ms)`（L104 常量、L429/L474/L705 使用）——抢不到本轮放弃，不让路由刷新堵死调用线程。synchronized 无法表达"等但等不过 X"。
2. **可中断地抢锁？**（`lockInterruptibly()`）关停序列里等待中的线程要能被 interrupt 召回。注意 `Object.wait` 自带可中断，所以"可中断地**等待条件**"≠"可中断地**获取锁**"——后者只有显式锁给。
3. **公平？**（`new ReentrantLock(true)`）严格 FIFO 防 barging 饥饿。synchronized 永远是悲观 barging 模型，不可配置。
4. **一把锁几路等待？**（多 Condition）严格意义只有 **LinkedBlockingQueue** 是此判据的真样本：`notEmpty`（L160，挂 takeLock）+ `notFull`（L167，挂 putLock）——**不同谓词的等待者分住不同条件队列，signal 各点各的人**。对照 DWQ：只有一枚 `available`（L953），leader 定时 awaitNanos / follower 不定时 await / offer signal 是**一个 Condition、三种参与方式**（java-base-5 §二），点名精度靠 leader-follower 协议限制等待集，不靠多队列。synchronized 的监视器只有一条隐式等待集——LBQ 若用 synchronized 写，两类谓词线程混住一栏，只能 `notifyAll` 惊群后全员重筛，且无法针对"哪路人"点名。另记防坑：JDK 21 的 ReentrantReadWriteLock 内部已无 notEmpty/notInterested/noWaiters 条件组（网上多条件例证多已过时），判据级说法须先 grep 当前版本。
5. **读写分离？**（ReadWriteLock 族）synchronized 连变体都没有；顺带记一笔专属能力：写锁内的**锁降级**（持写锁拿读锁再放写锁），StampedLock 一族又反过来不提供可重入。

五问全"不要"→ **synchronized 更优**：释放责任编译进结构（不可能忘 unlock/泄漏/乱序），逃逸分析下整锁可消除。RocketMQ 的正面样本 `ManyPullRequest`（longpolling L23-36）：synchronized 方法包 ArrayList，其中 `cloneListAndClear` 的"克隆+清空"必须整体原子——**这正是"一把互斥锁罩住 indivisible 状态转移"的教科书形状**，任何无锁容器都组合不出这个原子性。

一个纪律：ReentrantLock 的 `lock()` 与 `try{}finally{}` 之间不能有任何语句（unlock 进 finally 天经地义，lock 出 try 才是对的——lock 和 finally 之间崩掉，锁就永远没人解）。

### 验收问题

- [ ] 新版 `WAITING/COND/CANCELLED` 位模型下，park/unpark 之间的 Dekker 协议如何避免丢失唤醒？为什么 JDK 8 的 SIGNAL/PROPAGATE 传播链能被删掉？`tryAcquireShared` 返回负值意味着什么，返回 0 与正值在本机实现里行为有区别吗？
- [ ] 公平与非公平 ReentrantLock 的 `tryAcquire` 差别在哪一行？为什么重入判断挪到了 `initialTryLock`？barging 实际发生在哪一处？
- [ ] `signal()` 之后等待线程去了哪里（提示：它没醒）？为什么叫 transfer 而不是唤醒？`getAndUnsetStatus(COND)` 的原子认领在防什么竞态？
- [ ] AQS 为什么用模板方法而非组合？`getState/setState` 的"语义留给子类"设计利弊？（代价的现成证据：`AbstractQueuedLongSynchronizer` 是与 AQS 同步重写的 long-state 孪生版，JDK 内部却零消费者——见清单第 9 条末项）

---

## ④ ThreadPoolExecutor —— 有自定义实现，这站是"对答案"

**源码**：`ThreadPoolExecutor.java`、`LinkedBlockingQueue.java`

> 详述已拆出单篇：[java-base-4.md](java-base-4.md) —— 五种运行状态的 `ctl` 编码与大小顺序设计、状态迁移在源码里哪几行动手、interrupt worker 后的移除路径、池到 TERMINATED 后 worker 的存续形态，以及补充看点（三层并发控制 / Worker 继承 AQS / getTask 分叉 / 四种拒绝策略 / 异常路径 / 动态调参顺序规则）。
> 本站行号证据仍保留在本文件文末已验证事实清单第 12 条。

### 重点

```
execute 的 ctl 编码        高 3 位运行状态（RUNNING/SHUTDOWN/.../TIDYING/TERMINATED）
                          + 低 29 位 workerCount，一个 long/AtomicInteger 搞定
runWorker 的 getTask 循环  core 用 take()、non-core 用 poll(keepAlive)——
                          线程回收的本质是这条超时 poll
Worker 继承 AQS            不可重入锁语义区分"中断空闲 worker"与"中断执行中任务"
                          （state 0/1 模型 JDK 8~21 一字未动，但它跑的 acquire/release
                          自 JDK 17 起已是重写版 AQS：位模型 + Dekker 协议，见 ③ 节）
异常吞噬                   submit → FutureTask 保存异常；execute → 走
                          UncaughtExceptionHandler，runWorker catch 后不 rethrow
```

> **版本口径**：本站是五站里**唯一"JDK 8 笔记到今天还基本能用"的一站**——TPE 自身几乎没改。
> 本机 temurin-17.0.19 与 21.0.11 全文 diff：新增 18 行、删除 2 行，且**没有一行动过调度逻辑**
> （全部是 `SharedThreadContainer` 登记 + `finalize` 注解 + import）；配套读的
> `ScheduledThreadPoolExecutor` 与 `LinkedBlockingQueue` 两文件 17 与 21 **逐字节相同**。
> 相对 JDK 8 真正外部可见的差异只有两处：**`finalize` 不再兜底 `shutdown`**（JDK 9 起）与
> **`setCorePoolSize` 增加 `maximumPoolSize < corePoolSize` 校验**。而"大变化"发生在脚下：
> AQS（③）与 FutureTask（⑤）在 JDK 17 被重写，`Worker`/`termination`/`submit` 全部换了实现路径。
> 见文末清单第 12 条。

### Jaws 对应：EagerThreadPoolExecutor + WorkQueue

这是本站的独特优势——**你反着改造过 TPE，读源码是验证自己的每个 hack**：

- 标准 TPE"先入队后扩线程"；Eager 策略反转为"先扩到 max 再入队"，靠重写 `offer` 实现（`submittedTasksCount <= poolSize` 判定 + 第二次 offer 入真队列）。读 `execute` 源码才能确认为什么必须借 `ctl` 的 RUNNING 检查完成原子性，以及 `workerQueueSize=0` 时 `maxSubmittedTasks = maximumPoolSize + 0` 导致探针任务被直接拒绝的边界（实测踩过，配 `workerQueueSize=1` 修复）。
- benchmark 时池稳定在 corePoolSize=20：`submittedTasksCount ≤ poolSize` 使任务被空闲线程直接接走，永不触发扩容——读懂 getTask/wakeup 后才明白这个"不扩"是设计而非失效。
- `prestartAllCoreThreads()` 与拒绝策略里访问 `getActiveCount()`：统计逻辑已下沉进 `AbortPolicyWithStats.rejectedExecution(task, pool)` 的 `pool` 回调参数（天然就是 `ThreadPoolExecutor`），所以 `serverExecutor` 字段保持 `ExecutorService` 接口即可（`AbstractNettyServer` L57）——"统计访问点"与"字段声明点"职责分离。
- **Eager 的 hack 无版本依赖**：`execute` 至今仍然只是调 `workQueue.offer(command)`，`addWorker` 里 worker 数与 `ctl` 的原子校验、`workerQueueSize=0` 的边界也都未变，所以"重写 offer + 第二次 offer 入真队列"在 17/21 上行为与 8 一致。
- **两处版本差异对本项目的实际意义**：① 服务端池靠非守护 Netty 线程 + `prestartAllCoreThreads()` 保活，从不依赖 `finalize` 兜底，JDK 9 起的"finalize 不再 shutdown"只是顺手消除了 `newSingleThreadExecutor` 那类偶发 `RejectedExecutionException` 隐患（JDK-8145304）；② 目前全仓**0 处**调用 `setCorePoolSize/setMaximumPoolSize`，一旦把 core/max 接进 Nacos 动态配置，就必须遵守新校验的顺序规则（扩容先 max 后 core，缩容先 core 后 max）。
- 读本站时顺带确认 `SharedThreadContainer`（JDK 21）：worker 线程被登记进一个 `ThreadContainer` 供观测层按 executor 分组统计（`jcmd Thread.vthread_summary` 里能看到 `java.util.concurrent.ThreadPoolExecutor at ... [platform threads = N]`），`addWorker` 的 `t.start()` 变成 `container.start(t)`——**纯登记，语义零影响**；另外 JDK 21 的虚拟线程执行器 `newVirtualThreadPerTaskExecutor()` 返回的是私有 `ThreadPerTaskExecutor`（每任务一线程、无池无队列），**不是 TPE 被改造**，别把"21 线程池天翻地覆"误解成 TPE 重写。

### 验收问题

- [ ] 五种状态迁移图；shutdownNow 与 shutdown 在 `interruptIdleWorkers` 上的区别？
- [ ] 为什么 `Worker` 锁不能重入？`shutdown` 时如何避免中断正在执行任务的线程？
- [ ] `allowCoreThreadTimeOut` 打开后 core/non-core 的 getTask 路径如何统一？
- [ ] 四种拒绝策略 + CallerRuns 反压原理；Eager 的自定义拒绝策略为什么需要 `getActiveCount()`？
- [ ] 本机 17 与 21 的 TPE 源码到底差在哪几行？为什么 `SharedThreadContainer` 对使用者零影响、而 `finalize` 的变化影响真实？
- [ ] 相对 JDK 8 的两处外部可见差异是哪两个方法？动态调 core/max 的顺序规则是什么、为什么必须这样？
- [ ] `Worker` 的 `lock/unlock` 在 JDK 17+ 走的是新 AQS，为什么它的"不可重入"语义却完全没变？

---

## ⑤ ScheduledThreadPoolExecutor —— 终点站：Harbor 推送引擎的真相

**源码**：`ScheduledExecutorService.java`（接口）+ `ScheduledThreadPoolExecutor.java`（内部类 `ScheduledFutureTask`、`DelayedWorkQueue`）

> 详述已拆出单篇：[java-base-5.md](java-base-5.md) —— 接口四方法两组各一个区别、`DelayedWorkQueue` 手写最小堆与 leader-follower 三行等待、`ScheduledFutureTask` period 三态、`setNextRunTime` 两种周期 drift 处理、池参数藏在构造器、`delayedExecute` 四步、`removeOnCancelPolicy`、异常 = 静默停摆。
> 本站行号证据仍保留在本文件文末已验证事实清单第 10 条。

### Jaws 对应

- `PushDelayTaskEngine`：`scheduler.schedule(key, quietPeriod)` + `Set<ServiceKey>` 合并推送。读完本站能回答两个既有决策的正确性：为什么合并语义必须 Set 不能用队列（DelayQueue 会重复入队多次推送）；为什么 `scheduleQuietly` 在 scheduler 关闭时要原子 add+remove 回滚防死条目。
- 真正基于 STE 的调度全家：Harbor `HealthCheckScheduler`、`DistroProtocol`、`FailbackRegistry.retryExecutor`、Wire 的 `KEEPALIVE_SCHEDULER`/`RETRY_SCHEDULER`/`LIFECYCLE_SCHEDULER`（GOAWAY 后重连）、`ReferenceDestroyer`。
- 对照组——**不靠调度器**的两类：`NettyClient` 的 send-reconnect（`send.reconnect` 默认 true）是请求路径内的 lazy 重连：发现 `!isAvailable()` 时当场 `resetErrorCount() + open()`，零后台线程；心跳则是 Netty `IdleStateHandler` 在 EventLoop 上的 `schedule`，也不是 STE。读本站时对比三种定时机制（STE 堆 / EventLoop 定时任务 / 请求路径惰性检查）的适用边界。
- 消费端超时全家已统一为 STE：`AbstractClient.timeoutTimer` 是 `ScheduledThreadPoolExecutor(1, daemon)`，每个请求注册时挂一个 one-shot 超时任务（`registerCallback`/`removeCallback` + `timeoutMap`）。这段迁移本身就站在本站肩膀上：弃用 HashedWheelTimer 的原因是桶链表上 `Timeout.remove()` 实测负载下吃约 10% CPU，而 `removeOnCancelPolicy=true` 让取消变成 O(log n) 堆删除、不留 cancelled-entry 垃圾——读 DelayedWorkQueue 后才能判断这笔账（时间轮取消 O(1) vs 堆取消 O(log n)，为什么高基数短延时场景反而是堆赢）。
- 时间轮 vs 最小堆的调度哲学对比已随统一实现收敛：消费链路上剩下的定时需求全部由 STE 或 EventLoop 承担（见上两条）。
- `scheduleWithFixedDelay` 包 try-catch 是**正确防御**：不包的话异常导致周期任务静默停摆（见 [java-base-5.md](java-base-5.md) 第八节「异常 = 静默停摆」），Harbor 里曾出现看门狗误删连接的排查困难正源于此类静默。

### 验收问题

- [ ] 接口四方法分两组，每组内部那"一个区别"分别是什么？周期任务的三种终止方式，为什么异常没有任何日志就能看到？
- [ ] `schedule()` 的入队为什么需要 `q.offer` 后检查"是否成为新队首"再 signal available？
- [ ] take() 的三行等待（L1170/L1177/L1182）各对应什么情形？leader 是靠什么机制产生的、异常退出时闹钟靠什么交棒？为什么 N 个 worker 同时等一个到期时刻只需一次 `parkNanos`？
- [ ] `delayedExecute` 三次状态检查（入口 / 入堆后复查 / run() 首行）为什么每一处都允许与 shutdown 竞态失败而无害？复查回滚为什么是 `cancel(false)` 而不是像 `execute` 那样 `reject`？
- [ ] `ensurePrestart` 传 `null` firstTask 而不是任务本身，防的是什么？core=0 时哪条分支兜底、代价是什么？
- [ ] `canRunInCurrentRunState` 在 SHUTDOWN 态为什么要按"周期/非周期/已到期"三分？对应的两个策略开关各自影响 `onShutdown()` 清堆的哪个分支？
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

每站读完立即回项目做一次"源码考古笔记"：把与 Jaws 自定义实现（EagerThreadPoolExecutor、WorkQueue、PushDelayTaskEngine）的差异点补进对应类的英文注释，知识焊死在代码里。

## 附录：本计划依赖的已验证事实清单

以下结论均已对照本机 JDK 21（temurin-21.0.11）`src.zip` 源码逐条验证，非二手资料；第 9、10、12 条额外比对了本机 temurin-17.0.19（第 12 条给出两版全文 diff 行数）：

1. `TreeNode extends LinkedHashMap.Entry extends Node`，与 TreeMap 无代码复用（HashMap.java L1966 / LinkedHashMap.java L205）。
2. CHM 锁机制在 JDK 8 从 Segment 切换到"锁头节点 + CAS 空桶插入"。
3. HashMap resize 触发与分裂：常规扩容由 `putVal` 末尾 `if (++size > threshold) resize()` 判定（L668，比的是元素个数 `size`）；分裂时 `hash & oldCap` 决定留原位或移 `原位 + oldCap`。
4. 树化双条件：链长 ≥ 8 且 `table.length ≥ 64`（比的是当前桶数组长度，不是 threshold、也不是剩余可用容量），表长不足时扩容优先。
5. `LinkedBlockingQueue` 双锁（putLock/takeLock）结构——④ 的前置事实。
6. CHM 遍历顺序依赖桶物理布局 → 无序集合上禁止用顺序依赖运算（滚动哈希）计算 revision，用 XOR/求和等交换律运算——Jaws 实测 bug 与修复。
7. `java.util.ImmutableCollections`（JDK 9 起，List.of/Set.of/Map.of 的实现，List12/ListN/SetN/Map1/MapN + CollSer 序列化代理）：真不可变 vs `Collections.unmodifiableXxx` 包装视图的区别；拒绝 null 与 CHM/Optional 同一设计立场。跨线程发布的只读配置优先用 `X.of`。
8. `ArrayDeque`：环形数组 + 位运算取模，java.base 内 12 个文件使用（Resolver/URLClassPath/ZipFile 等，2026-09-27 grep 口径），Jaws 暂 0 处——协议解析/调度缓冲场景（HEADERS-DATA 帧排序、CONTINUATION 聚合）的候选优化项。
9. **AQS 在 JDK 17 起已是重写版**（本机 temurin-17.0.19 与 21.0.11 源码同模型逐行确认，SIGNAL/PROPAGATE 属 JDK 8~16 口径）：
    - Node 状态改为位模型 `WAITING=1 / COND=2 / CANCELLED=0x80000000`（AQS.java L462-464），模式区分改用节点子类型 `SharedNode/ExclusiveNode/ConditionNode`；`waitStatus`、`transferForSignal`、`setHeadAndPropagate` 均不存在。
    - 共享唤醒为链式点名：`releaseShared → signalNext(head)`（L1179-1184）+ 升为 head 时 `signalNextIfShared(node)`（L755，定义 L650）只看后继是否 `SharedNode`。`tryAcquireShared` 三态返回值的 javadoc 仍在（L948-956），但实现只做 `>= 0` 二分（L741、L1112-1114），**0 与正值无行为差别**（注释 L407-411 自认）。
    - park/unpark 靠 Dekker 协议：置 `WAITING` → 重试 acquire → 复查 status → 才 park；唤醒方 `getAndUnsetStatus(WAITING)` 后 unpark（注释 L345-349）。
    - `ReentrantLock` 重入分支已从 `tryAcquire` 上移到 `initialTryLock`：非公平 L223（裸 CAS，barging 点）/ 公平 L259（多 `!hasQueuedThreads()`）；两者 `tryAcquire` 的唯一差别是公平版 L280 的 `!hasQueuedPredecessors()`。`hasQueuedPredecessors` 新实现先乐观读 `head.next.waiter`，快照失效再从 tail 反向走（L1291-1297）。
    - `signal()` 不唤醒任何线程：`doSignal` 用 `getAndUnsetStatus(COND)` 原子认领节点后 `enqueue` 入主队列（L1540-1553 + L606-625），正常路径不 unpark；线程真正醒来是靠持锁者 `release → signalNext(head)`（L1092-1098）。这就是"叫 transfer 不叫唤醒"的根据。
    - 模板方法的代价实证（JDK 21 复核修正）：`AbstractQueuedLongSynchronizer.java`（1606 行）不再是"停在老版的整份副本"——它与 AQS **同代重写**（Node 成员 `prev/next/waiter/status` 逐行同款、`WAITING=1/COND=2/CANCELLED=0x80000000` 位模型、`signalNext/signalNextIfShared` 链式点名两版都有；AQLS 侧 L83-121/L262/L271）。真正的差异只剩三样：**state 字宽**（`volatile long state` L157，模板签名随之换宽）、**类 javadoc 一个天一个地**（AQS 类声明前约 290 行设计手册，AQLS 仅约 55 行）、**JDK 内部零消费者**（全 src 除自身外只在 `locks/package-info` 点名一次，Semaphore/CountDownLatch 全长在 AQS 上）。"每次演进同步两遍"的代价由 javadoc 归零与古董 import（`java.util.Date` 伺候已弃用签名）坐实——代码跟到了新一代，关注没跟到。
10. **`ScheduledFutureTask` 的状态机在 JDK 17/21 同样已不存在**（旧版 `WAITING → PROPAGATE → RUNNING → CANCELLED` 中的 `PROPAGATE` 服务于 `stopCompoundTask`，随 FutureTask 重写一并删除）：
    - `run()` 只剩四个分支：`!canRunInCurrentRunState → cancel(false)` / 非周期 → `super.run()` / 周期 → `super.runAndReset()` 成功才 `setNextRunTime() + reExecutePeriodic(outerTask)`（STPE.java L300-309）。
    - 方法名是 `setNextRunTime()`（L279，旧名 `setNextTime`）：`p > 0 → time += p`（fixedRate 追赶），否则 `time = triggerTime(-p)`（fixedDelay 重排）。
    - `period` 三态由字段注释直接定义（L194-200）：正 = fixed-rate、负 = fixed-delay、**0 = one-shot**；`isPeriodic()` 即 `period != 0`（L272）。
    - `FutureTask.state`：`NEW=0 → COMPLETING=1 → NORMAL=2 / EXCEPTIONAL=3`、`NEW → CANCELLED=4`、`NEW → INTERRUPTING=5 → INTERRUPTED=6`（FutureTask.java L92-99）；`runAndReset()` 在 `c.call()` 抛异常时 `setException(ex)` 并返回 false（L348-375），这串起了"异常 = 静默停摆"。
    - `DelayedWorkQueue` 靠 `ScheduledFutureTask.heapIndex` 定位堆内下标，使 `remove` 从 O(n) 降为 O(log n)，堆操作均在 siftUp/siftDown 里同步记录索引；非 `ScheduledFutureTask` 元素回退线性搜索（L903-923、L1044-1060）。
    - `DEFAULT_KEEPALIVE_MILLIS = 10L`（L443），四个构造器均固定 `super(corePoolSize, Integer.MAX_VALUE, 10, MILLISECONDS, new DelayedWorkQueue())`；`offer` 仅在 `queue[0] == e` 时 `leader = null; available.signal()`（L1105-1114）。
    - `take()`（L1163-1195）三行等待：堆空 `await()` L1170、follower `await()` L1177、leader `awaitNanos(delay)` L1182；leader 为普通字段 `private Thread leader`（L947），无选举协议；交棒 = finally 清字段（L1184-1185）+ 外层 `leader == null && queue[0] != null → available.signal()`（L1191-1192）；`poll(timeout, unit)`（L1197 起）同构，L1217 分叉处 `nanos < delay || leader != null` 取自己超时与队首到期之较早者；无超时 `poll()` 对未到期队首直接返回 null（L1154-1156）。
    - **接口文件** `ScheduledExecutorService.java`（本机全文 208 行，`@since 1.5` L88）：抽象方法仅 4 个（schedule L106/L122、fixedRate L164、fixedDelay L204），无任何 default 方法——JDK 22 新增两个桥接 `CompletableFuture` 的 default 方法（官方 changelog 口径，本机无 22 未逐行取证；`schedule(Supplier,...)` / `scheduleWithFixedDelay(Runnable,long,TimeUnit)` 在本机 21 已逐行确认不存在，网上见即版本口径不对）；周期三种终止条件 L132-144、"will not concurrently execute" L146-148、execute/submit 等价 delay=0 L48-52、相对延迟警告 L54-63、两个周期方法 `≤ 0` 抛 IAE L162/L204。
    - `cancel()` = `super.cancel()` 后只当 `cancelled && removeOnCancel && heapIndex >= 0` 才 `remove(this)`（L287-294）；heapIndex 的并发读被注释判为 benign（< 0 表示确定已移除），否则进 `remove()` 在锁内复查。
    - `delayedExecute` L338-348、`canRunInCurrentRunState` L316-325（两策略开关 L165-170）、`reExecutePeriodic` L356-366、`ScheduledFutureTask.run()` 首行判权 L301-302；`ensurePrestart` 定义在 TPE L1606-1612（STE 自身不定义）、`reject` 在 TPE L840-842——本机 17 与 21 的 STE 逐字节相同（见第 12 条 diff 结果），`reject`/`addWorker` 属第 12 条"骨架逐条仍在"；`ensurePrestart` 作为 TPE 命名方法的引入版本本机不可取证（JDK 8 口径下这段逻辑内联在 STE 的 delayedExecute 里，属二手未逐行验证），但 17/21 上的两条分支与 8 的行为等价。
11. **①② 两站在 JDK 21 下全部成立**，本轮补取的行号证据：
    - HashMap：扰动 `h ^ (h >>> 16)` L338；`tableSizeFor` L377；resize 高低位分裂 `if ((e.hash & oldCap) == 0)` L727（链表分支带 `preserve order` 注释）与 `((TreeNode<K,V>)e).split(...)` L720；`TREEIFY_THRESHOLD=8 / UNTREEIFY_THRESHOLD=6 / MIN_TREEIFY_CAPACITY=64` L260/267/275；`treeifyBin` L761-764；`TreeNode extends LinkedHashMap.Entry` L1966（LinkedHashMap.Entry L205）。
    - CHM：`sizeCtl` 字段 javadoc 直接列出三种角色（L793-800）——负数为初始化中（-1）或扩容中（-(1+活跃扩容线程数)），table 为 null 时存初始表长，初始化后存下次扩容阈值；位压缩发生在扩容态（`RESIZE_STAMP_BITS = 16`、`MAX_RESIZERS = (1<<16)-1`、`RESIZE_STAMP_SHIFT = 16`，L575/581/586）。**元素计数不在 sizeCtl 里**，而在 `baseCount`(long) + `CounterCell[]`（L790/815）。
    - CHM 元素访问：`tabAt` = `U.getReferenceAcquire`（L759-761）、`setTabAt` = `U.putReferenceRelease`（L767-770）——本机 17 与 21 一致（本机无 JDK 8，"早期写用 Volatile"属二手口径，未本地验证）。`DEFAULT_CONCURRENCY_LEVEL = 16` 仍定义但注释标为 unused（L523-526）。
    - `computeIfAbsent`（L1691-1778）四条路径：空桶 → CAS `ReservationNode` 并 `synchronized (r)` 跑 function；`fh == MOVED` → `helpTransfer`；**命中桶头（hash+key+val 全匹配）→ 不加锁直接返回**（源码注释 "check first node without acquiring lock"）；否则 `synchronized (f)` 锁桶头。递归更新由 `pred.next != null` 或撞上 `ReservationNode` 检出并抛 `IllegalStateException("Recursive update")`。`putVal` 的 null 拒写在 L1011。
    - 口径提醒：① 节统计表按 import 计（CHM 57 文件），全文匹配则为 59 文件；`LinkedHashMap` 的 11 是"提及文件数"（`new LinkedHashMap` 出现 22 次）。
12. **④ 站：TPE 骨架自 JDK 8 未变，变化在"脚下"与两处外部可见行为**（本机 temurin-17.0.19 与 21.0.11 `src.zip` 全文 diff：`ThreadPoolExecutor.java` 2129 → 2145 行，**+18 / −2**；`ScheduledThreadPoolExecutor.java` 与 `LinkedBlockingQueue.java` 两版**逐字节相同**；`FutureTask.java` diff 58 行。本机无 JDK 8，凡标"SE 8 javadoc"者取自 Oracle Java SE 8 官方 API 文档）：
    - **`finalize` 不再兜底关池（JDK 9 起）**：SE 8 javadoc 原文 "Invokes shutdown when this executor is no longer referenced and it has no threads"；21 是空实现 `protected void finalize() {}`（TPE.java L1498），`@implNote` 自陈 "Previous versions of this class had a finalize method that shut down this executor, but in this version, finalize does nothing"，注解由 17 的 `@Deprecated(since="9")` 变为 21 的 `@Deprecated(since="9", forRemoval=true)`（具体落在哪个版本本机无法界定，只知在 17~21 之间）。副作用是 JDK-8145304（`newSingleThreadExecutor` 因 finalize 被提前回收而偶发 `RejectedExecutionException`）在新版不可能再发生。
    - **`setCorePoolSize` 新增交叉校验**：SE 8 javadoc 只声明 `IllegalArgumentException - if corePoolSize < 0`；17 与 21 源码均为 `if (corePoolSize < 0 || maximumPoolSize < corePoolSize)`（21 L1560），javadoc 同步补上"or `corePoolSize` is greater than the maximum pool size"。而 `setMaximumPoolSize` 的 `<= 0 或 < corePoolSize` 在 SE 8 javadoc 里**已有**，不算新变化——两个 setter 的校验是对称了，不是都变严了。
    - **JDK 21 唯一实质新增：`SharedThreadContainer`**（`jdk.internal.vm`，类注释原话 "doesn't have an owner and is intended for unstructured uses, e.g. thread pools"）：字段 L485、构造器 L1321-1322 `SharedThreadContainer.create(Objects.toIdentityString(this))`、`addWorker` 里 `container.start(t)` 取代裸 `t.start()`（L953）、`tryTerminate` 末尾 `container.close()`（L736）；17 全文无 `container` 字样。用途是把 worker 归入可枚举/可关闭的 `ThreadContainer` 供观测层按 executor 分组统计（`jcmd Thread.vthread_summary`），语义零影响；引入边界本机只能界定在 17~21 之间，STE 未跟进（即本条开头那份"STE 17 与 21 逐字节相同"的 diff 结果）。
    - **骨架逐条仍在（行号取 21）**：`ctl` 仍是 `AtomicInteger(ctlOf(RUNNING, 0))` L387、`execute` 三段式与 "Proceed in 3 steps" 注释 L1339、`getTask` 的 core `take()` / non-core `poll(keepAlive)` 分叉 L1042、`runWorker` L1123、`Worker extends AbstractQueuedSynchronizer` L608-682（构造期 `setState(-1)` 抑制中断 L635、`tryAcquire` 的 CAS 0→1 L654、`lock()` = `acquire(1)` L668）、`ONLY_ONE` L829、`advanceRunState` L695、`processWorkerExit` L997、`getActiveCount` 仍是 mainLock 下遍历 `w.isLocked()`（`AbortPolicyWithStats` 依赖的就是它）。**公开 API 零新增**——整文件 `@since` 只有 1.5 与 1.6。
    - **地基换了实现**：`Worker` 的 lock/unlock、`interruptIdleWorkers` 的 `tryLock()`、`termination.awaitNanos()` 自 JDK 17 起全跑在重写版 AQS 上（见第 9 条）；`submit` 路径的 FutureTask 同样已重写（见第 10 条）；`AtomicInteger` 内部由 `sun.misc.Unsafe` 换为 `VarHandle`（JDK 9，属公开变更记录，本机不可逐行取证）。
    - **一处未逐行取证**：`runWorker` 内任务执行的 try/catch 形态——21 为 `try { task.run(); afterExecute(task, null); } catch (Throwable ex) { afterExecute(task, ex); throw ex; }`（L1143-1149），记忆中的 JDK 8 为 `Throwable thrown` + 三段 catch + `finally` 调 `afterExecute`；本机无 JDK 8，只确认语义等价，形态差异不写入结论。
