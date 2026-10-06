# ScheduledThreadPoolExecutor 源码精读：从接口到 DelayedWorkQueue（源码解析）

> 本文是 [`java-base.md`](java-base.md) ⑤ 站「ScheduledThreadPoolExecutor」的展开笔记。
> 源码取自本机 **Eclipse Temurin 21.0.11**（`$JAVA_HOME/lib/src.zip`），文中行号均为该版本实测行号。
> 版本口径：本机 17 与 21 的 `ScheduledThreadPoolExecutor.java` 逐字节相同（diff 结果见 [`java-base.md`](java-base.md) 文末清单第 12 条）；
> `FutureTask` 与 AQS 的重写发生在 JDK 17，本站多处结论依赖之（见该清单第 9、10 条，行号证据保留在 java-base.md 附录）。

## 一、接口先行：`ScheduledExecutorService` 四方法，两组各一个区别

**分组逻辑**：四方法在实现层的投影就是 `period` 三态——两个 `schedule` 落 `period=0`，
两个周期方法分落 `>0`/`<0`。先看接口再看实现，顺序即依赖。

**组一：两个 `schedule` 重载（一次性，接口 L106/L122）**。唯一区别在类型系统层面的
"产出值与否"：Runnable 版完成时 `get()` 返回 null（L100-101），Callable 版返回
`ScheduledFuture<V>` 可提取结果（L117）；到 STE 里是同一行代码的差别——构造的
`ScheduledFutureTask` 只 `result` 字段一个传 null 一个传 callable。即 ④ 的
`execute`/`submit` 二选一在定时维度的复刻，只多一个 `delay`。两条公共约定：
允许 0/负 delay 视为立即执行（L50-52）——但 **period/delay 不允许非正**，两个周期
方法 `≤ 0` 直接 `IllegalArgumentException`（L162/L204），恰好与 schedule 形成对照。

**组二：两个周期方法（L164/L204）**。唯一区别是周期的"锚"打在哪：

- **scheduleAtFixedRate**（L129-130 原文列序列）：相邻两次**开始**之间；计划时刻 `initialDelay + n×period` 绝对锚定，超时一轮会"追赶"（连补跑，对应 `setNextRunTime` 的 `time += p`）。
- **scheduleWithFixedDelay**（L171-173：between the termination of one execution and the commencement of the next）：**上一轮结束**到**下一轮开始**之间；每轮跑完重新起表，无全局锚点、无追赶概念（`time = triggerTime(-p)`）。
- **共同底线**：绝不并发执行同一任务（L146-148："will not concurrently execute"——重排发生在 `runAndReset()` 返回之后，本轮没跑完根本不会回堆）。

选型直觉：fixedRate = 时钟语义（要求固定频率、能接受偶发连跑：对账扫描、指标采样）；
fixedDelay = 歇脚语义（两轮之间必有 idle 间隔、给慢任务留缓冲）——Jaws 的
`HealthCheckScheduler`、`FailbackRegistry.retryExecutor` 全用 fixedDelay 即此立场。

**三种终止条件（L132-144，接口 javadoc 最值钱的一段）**：周期序列只有三种结束方式
——显式 cancel、executor 终止、**某轮执行抛异常**；异常时 future 变 `isDone()=true`，
异常存在 future 里，**只有 `get()` 才以 `ExecutionException` 现身**（L138-140）——本站
"异常 = 静默停摆"的接口层法条：不经 `runWorker` 的 catch，异常被 FutureTask 状态机
吞掉；Jaws 里 `scheduleWithFixedDelay` 包 try-catch 的正确防御依据就在这三行。

**两条接口级公共约定**：① 全部 delay/period 是**相对量**，实现上换算成
`triggerTime() = System.nanoTime() + unit.toNanos(delay)`，javadoc 特意警告相对延迟
到期与墙钟 `Date` 不重合（NTP 校时/时钟漂移不影响调度，L54-63——"Java 定时为什么用
nanoTime 不用 currentTimeMillis"的官方说法）；② `execute`/`submit` 在 STE 上等价于
`delay=0` 的 schedule（L48-50）——所以 STE 的队列永远是 `DelayedWorkQueue`，普通
提交也进堆。

## 二、DelayedWorkQueue —— 手写最小堆

**前置：最小堆是什么形状**。三层认知，缺一个就看不动 DelayedWorkQueue：

1. **逻辑上是一棵完全二叉树 + 堆序性**。形状约束：除末层外全满、末层从左向右贴边；序约束：父 ≤ 子（最小堆，`queue[0]` 即全局最小=最早到期——**take() 只盯堆顶这一件事的全部合法性来源**）。注意它只保证父子关系，兄弟间、跨层间不排序——**堆≠有序**（PriorityQueue 的 toArray 无序同理）。
2. **物理上是一个数组，不是树**。完全二叉树的编号规律让指针成为多余：父 `(i−1)/2`，左子 `2i+1`，右子 `2i+2`。示例树压平就是 `[1, 3, 2, 7, 5, 4]`——这正是本节开头"`Object[]` 直接存、无节点对象分配"的几何前提，顺带买到数组局部性、树高恒 ≤ log₂n：

   ```
        1                  ← 堆顶 = 最早到期
       / \                 索引: 0  1  2  3  4  5
      3   2                值:   [1, 3, 2, 7, 5, 4]
     / \ /
    7  5 4
   ```

3. **不变式只靠两个动作维持**：插入 `siftUp`（上浮）、摘顶 `siftDown`（下沉）。而"删任意节点"不是堆的原生操作（定位就要 O(n)）——第七节 `removeOnCancelPolicy` 能做 O(log n) 删除，全靠 `ScheduledFutureTask.heapIndex` 把"任务→数组下标"做成显式反向索引，sift 时同步记账。

一句话：**堆 = 长得像完全二叉树、住在数组里、只保证堆顶最值**的结构。


`siftUp/siftDown`，`Object[]` 直接存，无节点对象分配。与 `DelayQueue` 的关系：同一问题（取最近到期）的演化实现——LBQ 的 put/take Condition 换成了 `available` + `q.size()` 双检 + "队首变化才 signal" 的 leader 式优化。

**与 take 相对的一侧：offer——元素怎么找到自己的位置（siftUp，L967）**

take/offer 是堆的一对入口出口：take 摘顶后 `siftDown` 把补进来的尾元素往下压（见 L1163 段的 `finishPoll→siftDown`），offer 则从**数组尾部开洞**、让新元素沿父链上浮。`offer`（L1095）把这个循环**内联**了一份（`i = size++` 起步），具名 `siftUp(int k, key)` 真正服务的是 `remove(Object)` 任意摘除后的再平衡（与 `siftDown`（L985）成对调用，元素只会朝一个方向走，另一把零步退出）。逐行看这个洞技术：

前提：调用方已把 `key` 从位置 `k` **取出**——`queue[k]` 是个空洞，算法找的是"回填到哪"。

```java
while (k > 0) {                          // 洞还能上浮（index 0 是堆顶，无父即终点）
    int parent = (k - 1) >>> 1;          // 父下标 ⌊(k−1)/2⌋：数组存树的另一条索引公式
    RunnableScheduledFuture<?> e = queue[parent];
    if (key.compareTo(e) >= 0)           // key 不比父更早到期 → 已满足 父≤子，就地停
        break;
    queue[k] = e;                        // ★ 父下沉填洞——是移位不是交换：每层一次写
    setIndex(e, k);                      //   同步记账：任务→下标 反向索引
    k = parent;                          //   洞上移一层
}
queue[k] = key;                          // 归宿找到，回填
setIndex(key, k);
```

`compareTo` 比的是到期时刻（`ScheduledFutureTask.compareTo`：先 `time`，平手按 `sequenceNumber` 提交序决胜）——"谁小"="谁先到期"。走一遍 `[1,3,2,7,5,4]` 的洞 `k=6` 插 `key=0`：

```
洞6  父2(值2) 0<2 → 2 下沉       [1,3,_ ,7,5,4,2]
洞2  父0(值1) 0<1 → 1 下沉       [_,3,1,7,5,4,2]
洞0  k==0 出环 → 回填             [0,3,1,7,5,4,2] ✓  三步，每步一写
```

三个设计点：
1. **"洞+下沉"而非交换**：每层一次写入+一次记账（交换要三步写），最后统一回填——插入排序的移位技巧，摊到 O(log n) 层。
2. **`setIndex` 是 `remove` 的前置**：上浮途中同步维护 `heapIndex`，"删任意任务"才能 O(1) 定位、O(log n) 补位——第七节 `removeOnCancelPolicy` 的成本账全靠这两行记账撑着（前置§第3层说的"显式反向索引"就是它）。
3. **`>=0` 即 break（等于也停）**：不比父更早就地住，不无谓上浮——省步数之外还让堆形态唯一、行为可复现。

**take() 里的三行"等"（L1163-1195）**——到期判断 `delay <= 0` 之外，未到期时线程全在这三行里：

| 行 | 谁在等 | 等什么 |
|---|---|---|
| L1170 `available.await()` | 任何线程，堆空时 | 不定时，直到 `offer` 的 signal |
| L1177 `available.await()` | follower（已有人是 leader） | 不定时，直到 leader 到点交棒 signal / 新队首 signal |
| L1182 `available.awaitNanos(delay)` | **leader**（第一个到达等待分支的线程） | 定时 `delay` 纳秒——整个堆唯一的闹钟本体，时长就是 `getDelay` 返回的剩余量 |

**leader 是什么**：不是选举协议，就是一个 `private Thread leader` 字段（L947）——leader-follower 模式（DNS 服务器文献起源）的"leader"指**代表全堆承担精确定时等待的那个人**：谁先走到 L1178 的 else 分支，谁把自己写进字段（L1180）去 `awaitNanos(delay)`；后来者看到 `leader != null` 就退化成 follower 睡不定时 `await()`，靠点名叫醒。收益：N 个 worker 等同一个到期时刻时，`parkNanos` 定时器只挂 1 次，而不是 N 次到点唤醒后 N-1 次空手而归——"堆里任何时刻最多一次精确定时"。

三条配套不变式：① 交棒——leader 在 finally 里清字段（L1184-1185），外层 finally 见 `leader == null && queue[0] != null` 就 `available.signal()`（L1191-1192）点名新 leader，正常取走/中断/更早任务插入三路退出都保证闹钟有人接手；② `awaitNanos` 本身走 ③ 站的 ConditionObject 链：`addConditionWaiter` → `fullyRelease` **释放堆锁**（等待期间 offer/remove 不受阻）→ `parkNanos` 每圈补差防超时丢失 → 醒后 `reacquire` 抢回锁回到 `for(;;)` 圈首重算 `getDelay`；③ offer 侧只在 `queue[0] == e`（新任务成了新队首）时 signal（L1105-1114）——对等待者唯一有意义的事件是"最早到期提前了"。

**摘除动作：finishPoll + siftDown——take 的下半场（L1140 / L985）**。上面三行"等"只管**何时醒来**；醒来判定 `delay<=0` 之后，摘走堆顶这一步与"等"完全无关，take（L1174）与 timed poll（L1213）共用这个 `finishPoll`：

```java
private RunnableScheduledFuture<?> finishPoll(RunnableScheduledFuture<?> f) {
    int s = --size;                        // 缩号：末位 s 成为可摘走的"自由位"
    RunnableScheduledFuture<?> x = queue[s];   // 取末叶——二叉堆删除的标准起手：尾补头
    queue[s] = null;                       // 清空尾："堆只活在 [0,size)" 的不变式复原 + 防泄漏
    if (s != 0)                            // size 已为 0（原堆只剩 f，x==f 刚被置 null）就不必堆化
        siftDown(0, x);                    // x 当洞从根位下沉找归宿
    setIndex(f, -1);                       // 销账：f 已出堆——heapIndex 生命周期到这里闭合
    return f;
}
```

三个点：**① 尾补头**保数组永远紧凑——完全二叉树的"形状"不被破坏，这是 O(1) 摘顶的前提（链式堆做不到）；**② `setIndex(f,-1)`** 与入堆时 sift 沿途的记账成对——之后谁再 `remove(f)` 按 -1 即知"已出堆"；**③ `siftDown` 单向就够**：x 被放在**堆顶**，index 0 无父，只可能向下违例——对比 `remove(Object)` 的任意位置，那里才需要 `siftUp`+`siftDown` 双向各试一把。

`siftDown`（L985-1001）逐行：

```java
int half = size >>> 1;                 // 非叶下标上界：跌进叶子层即归宿，不去比不存在的子
while (k < half) {
    int child = (k << 1) + 1;          // 左子 2k+1
    RunnableScheduledFuture<?> c = queue[child];
    int right = child + 1;
    if (right < size && c.compareTo(queue[right]) > 0)
        c = queue[child = right];      // 两个孩子里选更小的（右可能不存在）
    if (key.compareTo(c) <= 0)         // key 不大于最小子 → 洞位放它即保住 父≤子
        break;
    queue[k] = c;                      // ★ 小子往上提填洞——与 siftUp 镜像的洞技术
    setIndex(c, k);                    //   同样每层一次写 + 一次记账
    k = child;
}
queue[k] = key;
setIndex(key, k);
```

核心一条：**必须往"更小的孩子"沉**——往大了沉，小的那个孩子就和父违例了。`>=break/<=break` 的对称（siftUp 是 `key≥父` 停，siftDown 是 `key≤小子` 停）加上"每层一写+setIndex"，两把 sift 其实是同一个洞技术朝两个方向各推一遍——**堆的写路径全部动作，就这两段共 30 行**。

**场景推演：corePoolSize=2，两个同周期定时任务——两个人怎么分工等**。前置节律：`runWorker` 跑完一轮任务，先 `setNextRunTime + reExecutePeriodic`（带着下一拍到期时刻重新进堆）再回到 `take()`；fixedRate 的 `time += p` 对齐使两个任务的下一到期时刻 **T 精确相同**。

```
线程1 先到 take():  peek 队首 delay = T−now > 0
                    leader==null → 自任 leader（L1180）→ awaitNanos(delay)（L1182，全堆唯一闹钟）
                    await 原子释放 available 锁 → 放行线程2
线程2 晚数百μs:      队首仍未到期 且 leader!=null → await()（L1177，不定时、无闹钟）
T 到点:              leader 定时醒 → 循环重查 → delay<=0 → finishPoll 取走任务A
                     finally 置 leader=null；堆非空 → signal()（L1191-92）点名 follower
                     follower 重查：任务B 同拍已到期 → 直接取走，不再等
```

典型结局不是"两个都 awaitNanos"，是**"一个定时等、一个不定时等"**。三个分支各自成立：

1. **双双到期则无人等**：两线程进 `take()` 时队首 `delay<=0`，各自锁内 peek+finishPoll 一人一个直取——take() 没有"一次只发一个号"的语义，原子性由 `available` 锁保证。
2. **fixedDelay 必错拍**：`time = triggerTime(−p)` 是"跑完再推"，执行时长差异使两拍错开；leader 取走早拍、signal 唤醒 follower，重查发现晚拍未到期 → **follower 变成新 leader 挂新闹钟**。闹钟换了人，仍只有一只。
3. **晚到者可能先动水位**：慢的那个 `reExecutePeriodic` 进堆若成为新队首，offer 的"队首变化才 signal"会戳醒等待者——协议保证**最坏晚拿、绝不丢**，不要求线程步调一致。

回到"会在同一时刻调 take() 吗"：严格意义的同刻不存在也不必存在——**可以同时身处 take()，绝不同处决策临界区**（peek-判定-摘除都在 `available.lock()` 内）。正确性建立在"锁内重查"上，而非线程同步上——这是 leader-follower 全段的判词。

## 三、ScheduledFutureTask —— 无自有状态机

JDK 17 起随 FutureTask 重写，旧版 `WAITING → PROPAGATE → RUNNING → CANCELLED` 已消失。只靠 `FutureTask.state`（NEW→COMPLETING→NORMAL/EXCEPTIONAL、NEW→CANCELLED）+ `period` 三态：0 一次性 / >0 fixedRate / <0 fixedDelay（`isPeriodic()` 即 `period != 0`）。

**本类全部的"额外职责"就是覆写的 `run()`（L300-309）**——javadoc 自陈 "Overrides FutureTask version so as to reset/requeue if periodic"，三个分支即三处交叉引用的合体：

1. `!canRunInCurrentRunState(this)` → `cancel(false)`（L301-302）——`delayedExecute` 双检后仍竞态留在堆里的任务，开跑前最后一刻重查同一谓词，这就是第六节所称"第三道防线"的现场；
2. `!isPeriodic()` → `super.run()`——一次性任务完全交回 FutureTask 状态机，本类零参与；
3. 周期任务 → `super.runAndReset()` 返回 true 才 `setNextRunTime() + reExecutePeriodic(outerTask)`（L305-307）——本轮没跑完或跑挂，都不会回堆，接口第一节"绝不并发执行同一任务"的实现保证就在这一步。

**runAndReset 为什么配当"是否续排"的判据**（FutureTask L348-376）：跑 `c.call()` 但**不置 result**（L358 注释 "don't set result"），成功路径 state 停在 NEW，末尾 `return ran && s == NEW`；异常走内层 catch → `setException` 置 EXCEPTIONAL → 返回 false → 不回堆——第八节"异常 = 静默停摆"的出口就是这个 boolean。finally 里 `runner = null` 后重读 state、`s >= INTERRUPTING` 时 `handlePossibleInterrupt` 自旋等 CANCEL 落定，防的是"被 cancel 的任务误判成功回了堆"。

至此"无自有状态机"的完整含义闭环：**周期语义不占任何状态位，只体现为 `run()` 出口处对 `reExecutePeriodic` 的一次调用决策**。

## 四、setNextRunTime —— 两种周期的 drift 处理差异

旧名 `setNextTime`。fixedRate 是 `time += p`，基于上次计划时间，跑超时后连续补跑追赶；fixedDelay 是 `time = triggerTime(-p)`，基于本轮触发时刻重排。

## 五、池参数藏在构造器

所有 STE 构造器固定传 `super(corePoolSize, MAX_VALUE, 10ms)`——`maximumPoolSize` 无意义（源码注释：core 与 max "effectively identical"），多余线程闲置 10ms 即回收；javadoc 同时警告别把 core 设 0 或开 `allowCoreThreadTimeOut`，否则任务到期无人取。

## 六、delayedExecute（L338-348）—— ④ execute 三段式在本站的塌缩形态

四个 `schedule` 与被包装成 delay=0 的 `execute`/`submit` 全部以它收尾；类头注释第 2 条点名 "simplifies some execution mechanics (see delayedExecute)"（L147-150），做的减法 = 无界堆（`offer` 恒成功，砍掉"入队失败转 addWorker"第三段）+ core==max（砍掉 firstTask 直塞新线程的判断）。

四步流程：

1. `isShutdown()` → `reject(task)`——`reject` 是 TPE 专为 STE 留的包级方法（TPE L840 "for use by ScheduledThreadPoolExecutor"），走 handler 策略，关停后 `schedule` 抛 REE 的出处。
2. 入堆——顺带"成为新队首才 signal available"。
3. 双检 `!canRunInCurrentRunState && remove → cancel(false)`——与 `execute` 复查的 `!isRunning && remove → reject` 形似神异：任务已进过堆，语义是"撤销"不是"拒收"，不抛 REE；remove 失败（已被 worker 取走）落 else 良性放过，第三道防线在 `ScheduledFutureTask.run()` 首行重查同谓词后 `cancel(false)`（L301-302）。
4. `ensurePrestart`——不在 STE 在 TPE（L1606-1612，"arranges that at least one thread is started even if corePoolSize is 0"），恒传 null firstTask，javadoc 括弧给出理由 "the task (probably) shouldn't be run yet"：新线程只许去 getTask 挂着等到期，这一步保证的是"到期那一刻有取主在场"而非"任务立刻有人跑"；`wc==0` 兜底起 non-core（10ms keepAlive）正是上文第五节"池参数藏在构造器"一条的注脚——core 设 0 不致死锁，但线程闲置即回收会反复起线程。

谓词 `canRunInCurrentRunState`（L316-325）比 TPE 的 `isRunning` 多出"任务维度"：STOP 一票否决；SHUTDOWN 分岔读两个 volatile 策略开关（L165-170，即两个 `setXxxPolicy` 背后字段，`onShutdown` 清堆读同一对）——周期任务看 `continueExistingPeriodicTasksAfterShutdown`（默认 false），非周期看 `executeExistingDelayedTasksAfterShutdown`（默认 true）或 `getDelay(NANOSECONDS)<=0`（已到期算 SHUTDOWN 承诺跑完的存量）。

孪生方法 `reExecutePeriodic`（L356-366）javadoc 自陈 "Same idea as delayedExecute except drops task rather than rejecting"：入口不 reject，关停时周期任务静默 `cancel(false)`；两个 if 极性写成 `canRun || !remove`，与 `!canRun && remove` 同一德摩根式竞态处理。

## 七、removeOnCancelPolicy（默认 false）

`cancel()` 默认只置 CANCEL 标志、节点留在堆里直到到期被 poll 剔除（O(1) 标记 + 摊销清理）；`setRemoveOnCancelPolicy(true)` 改为立即堆删除——`DelayedWorkQueue` 里 `ScheduledFutureTask` 自己记 `heapIndex`（堆内下标），删除从 O(n) 搜索降到 O(log n)，非 `ScheduledFutureTask` 元素回退线性搜索——高频取消场景防 cancelled-entry 垃圾堆积。

## 八、异常 = 静默停摆

周期任务抛一次异常 → `runAndReset` 内部 catch → `setException` 置 EXCEPTIONAL → `runAndReset` 返回 false → `run()` 不再 `reExecutePeriodic`，该任务永远不再进堆；异常存在 future 里没人 get，因此没有任何日志（不经过 `runWorker` 的 catch）。

---

> Jaws 对应与验收问题仍留在 [`java-base.md`](java-base.md) ⑤ 站；本节各小节的行号证据清单见该文件文末第 10 条。
