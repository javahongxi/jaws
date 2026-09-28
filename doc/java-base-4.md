# ThreadPoolExecutor 的 5 种运行状态（源码解析）

> 本文是 [`java-base.md`](./java-base.md) ④ 站「ThreadPoolExecutor」的展开笔记。
> 源码取自本机 **Eclipse Temurin 21.0.11**（`$JAVA_HOME/lib/src.zip`），`ThreadPoolExecutor.java` 共 2145 行；
> 文中行号均为该版本实测行号。版本口径：这套状态机骨架自 JDK 1.5 起未变，本机 17 与 21 的调度逻辑逐字节相同。

## 一、5 种状态住在哪：`ctl` 一个 int 里

`ThreadPoolExecutor` 没有单独的「状态字段」，运行状态和 worker 数量被**打包进同一个 `AtomicInteger ctl`**（L387）：

```java
private final AtomicInteger ctl = new AtomicInteger(ctlOf(RUNNING, 0));
private static final int COUNT_BITS = Integer.SIZE - 3;   // = 29
private static final int COUNT_MASK = (1 << COUNT_BITS) - 1;

// runState is stored in the high-order bits   （高 3 位存状态）
private static final int RUNNING    = -1 << COUNT_BITS;   // L392
private static final int SHUTDOWN   =  0 << COUNT_BITS;   // L393
private static final int STOP       =  1 << COUNT_BITS;   // L394
private static final int TIDYING    =  2 << COUNT_BITS;   // L395
private static final int TERMINATED =  3 << COUNT_BITS;   // L396
```

- **高 3 位 = runState，低 29 位 = workerCount**，所以 worker 数上限是 `2^29-1`（约 5 亿）。
- 拆包/装包三个位运算工具（L399-401）：

```java
private static int runStateOf(int c)     { return c & ~COUNT_MASK; }  // 取高位状态
private static int workerCountOf(int c)  { return c & COUNT_MASK; }   // 取低位计数
private static int ctlOf(int rs, int wc) { return rs | wc; }          // 合并
```

## 二、为什么状态值的大小顺序至关重要

类注释原话（L362-363）：「**The numerical order among these values matters, to allow ordered comparisons.**」
五个值被刻意设计成单调递增（RUNNING 是负数最小，TERMINATED 最大），于是判断「是否进入/超过某状态」根本不用拆包，直接比大小：

```java
private static boolean runStateLessThan(int c, int s) { return c < s; }   // L408
private static boolean runStateAtLeast(int c, int s)  { return c >= s; }  // L412
private static boolean isRunning(int c) { return c < SHUTDOWN; }          // L416
```

`isRunning(c)` 能写成 `c < SHUTDOWN` 正是因为 **RUNNING 是唯一负值**（`-1<<29`），低位 workerCount 恒非负，
所以只要状态是 RUNNING，整个 `ctl` 一定 `< SHUTDOWN(0)`。对外 API 全部靠它：

```java
public boolean isShutdown()    { return runStateAtLeast(ctl.get(), SHUTDOWN); }   // L1437
public boolean isTerminated()  { return runStateAtLeast(ctl.get(), TERMINATED); } // L1462
public boolean isTerminating() {                                                    // L1457
    int c = ctl.get();
    return runStateAtLeast(c, SHUTDOWN) && runStateLessThan(c, TERMINATED);
}
```

## 三、5 种状态各自的语义（类注释 L353-360 逐字）

| 状态 | 源码注释语义 | 通俗解释 |
|---|---|---|
| **RUNNING** | Accept new tasks and process queued tasks | 初始态。接受新任务 + 处理队列里任务 |
| **SHUTDOWN** | Don't accept new tasks, but process queued tasks | **不再接新任务，但队列里已存的要跑完**（`shutdown()` 触发）|
| **STOP** | Don't accept new tasks, don't process queued tasks, and interrupt in-progress tasks | 不接新任务、**丢弃队列任务、并中断正在执行的任务**（`shutdownNow()` 触发）|
| **TIDYING** | All tasks have terminated, workerCount is zero, the thread transitioning to TIDYING will run the `terminated()` hook | worker 全走完、`workerCount==0`，抢到迁移动作的那个线程去跑 `terminated()` 钩子 |
| **TERMINATED** | `terminated()` has completed | `terminated()` 跑完，池彻底结束 |

关键区分 **SHUTDOWN vs STOP**：SHUTDOWN 是「温和收尾」——已入队任务照常执行完；STOP 是「强制熄火」——队列清空、在跑的任务收中断。

## 四、状态迁移：源码里到底在哪几行动的手

类注释给出的迁移图（L366-375），且注明「monotonically increases… **but need not hit each state**」（可跳状态，比如直接 RUNNING→STOP）：

```
RUNNING -> SHUTDOWN            调用 shutdown()
(RUNNING or SHUTDOWN) -> STOP  调用 shutdownNow()
SHUTDOWN -> TIDYING            队列和池都空时
STOP -> TIDYING                池空时
TIDYING -> TERMINATED          terminated() 钩子跑完
```

### 1. RUNNING → SHUTDOWN（`shutdown()`, L1390）

```java
public void shutdown() {
    mainLock.lock();
    try {
        checkShutdownAccess();
        advanceRunState(SHUTDOWN);     // CAS 把状态推到 SHUTDOWN
        interruptIdleWorkers();        // 只中断「空闲」worker
        onShutdown();                  // STE 用来取消延迟任务的钩子
    } finally { mainLock.unlock(); }
    tryTerminate();
}
```

推进状态靠 `advanceRunState`（L695），它只在「当前 < 目标」时才 CAS，保证状态单调不回退：

```java
private void advanceRunState(int targetState) {
    for (;;) {
        int c = ctl.get();
        if (runStateAtLeast(c, targetState) ||
            ctl.compareAndSet(c, ctlOf(targetState, workerCountOf(c))))
            break;
    }
}
```

注意 `interruptIdleWorkers()`（L799）里用 `w.tryLock()`——**能抢到 Worker 锁说明它正空闲等待任务（没在跑任务），才 interrupt**。
这就是 `shutdown` 不会打断正在执行任务的原因。

### 2. (RUNNING|SHUTDOWN) → STOP（`shutdownNow()`, L1421）

```java
public List<Runnable> shutdownNow() {
    mainLock.lock();
    try {
        checkShutdownAccess();
        advanceRunState(STOP);      // 推到 STOP
        interruptWorkers();         // 中断所有 worker，不管忙闲
        tasks = drainQueue();       // 把队列任务掏出来返回给调用方
    } finally { mainLock.unlock(); }
    tryTerminate();
    return tasks;
}
```

与 `shutdown` 的三点差别：目标状态是 STOP；用 `interruptWorkers()`（L774，对每个 `w.interruptIfStarted()`，**不 tryLock**，
所以在跑任务的线程也被中断）；并 `drainQueue()` 丢弃未执行任务。

### 3. SHUTDOWN / STOP → TIDYING → TERMINATED（`tryTerminate()`, L715）

这是唯一进入终态的通道，一个自旋：

```java
final void tryTerminate() {
    for (;;) {
        int c = ctl.get();
        if (isRunning(c) ||                                       // 还在 RUNNING 不收尾
            runStateAtLeast(c, TIDYING) ||                        // 已 TIDYING/TERMINATED
            (runStateLessThan(c, STOP) && ! workQueue.isEmpty())) // SHUTDOWN 但队列还没跑完
            return;
        if (workerCountOf(c) != 0) {          // 该收但还有 worker 没退，
            interruptIdleWorkers(ONLY_ONE);   // 打断一个空闲 worker 让信号传播下去
            return;
        }
        mainLock.lock();
        try {
            if (ctl.compareAndSet(c, ctlOf(TIDYING, 0))) {   // CAS：SHUTDOWN/STOP -> TIDYING
                try {
                    terminated();                            // 跑销毁钩子（默认空实现）
                } finally {
                    ctl.set(ctlOf(TERMINATED, 0));           // TIDYING -> TERMINATED
                    termination.signalAll();                 // 唤醒 awaitTermination
                    container.close();                       // JDK 21 新增：关闭线程容器
                }
                return;
            }
        } finally { mainLock.unlock(); }
    }
}
```

几个源码级要点：

- **迁移到 TIDYING 的前置条件**：`workerCount == 0`，且——SHUTDOWN 态要求队列也空（`workQueue.isEmpty()`，L448 注释解释为何用 `isEmpty` 而非 `poll()==null`，兼容 DelayQueue），STOP 态不要求队列空（反正已 drain）。
- **TIDYING 是个「独占过路态」**：`ctl.compareAndSet(c, ctlOf(TIDYING, 0))` 保证只有一个线程成功转入，由它负责跑 `terminated()`；跑完立刻 `ctl.set(ctlOf(TERMINATED, 0))`。这也是为什么注释说「transitioning to TIDYING 的那个线程 will run terminated()」。
- `tryTerminate()` 在多处被调用（`shutdown`/`shutdownNow` 末尾、worker 退出 `processWorkerExit`、`getTask` 里检测到状态不对时），任何「可能让池变空」的动作后都要调它，才能推进终态。
- L736 的 `container.close()` 是 JDK 21 相对 17 唯一实质变化（`SharedThreadContainer`，见 [`java-base.md`](./java-base.md) 第 12 条），纯资源登记，语义零影响。

`awaitTermination()`（L1466）则一直等在 `termination` 条件上，直到状态 `>= TERMINATED` 才返回，
呼应注释「Threads waiting in awaitTermination() will return when the state reaches TERMINATED」。

## 五、一句话串起来

`ctl` 高 3 位承载 RUNNING→SHUTDOWN/STOP→TIDYING→TERMINATED 五个**单调递增**状态
（顺序性是 `runStateAtLeast`/`isRunning` 免拆包比较的基础）；`shutdown()` 走 SHUTDOWN 只中断空闲 worker 并跑完队列，
`shutdownNow()` 走 STOP 中断全部 worker 并掏空队列；`advanceRunState` 负责前半段迁移，
`tryTerminate` 在 `workerCount==0`（且 SHUTDOWN 下队列已空）时 CAS 进 TIDYING、跑 `terminated()` 钩子后置为 TERMINATED 并唤醒等待者。

## 六、对 worker 执行 interrupt 后会怎样？会从 workers 里移除吗？

直接结论：**`interrupt` 本身不会把 worker 从 `workers` 集合里移除**。它只是「打断 worker 当前的等待/执行」，让它跳出 `runWorker` 的循环；
真正的移除（以及 `workerCount` 扣减）发生在循环退出后的 `processWorkerExit` 里。下面按 worker 当时所处的位置拆开讲。

### 1. 先分清两种 interrupt

| 方法 | 谁能被中断 | 谁用 |
|---|---|---|
| `interruptIdleWorkers`（L799）| 只有 `w.tryLock()` 成功的，即**正空闲、阻塞在 `getTask` 等任务**的 worker | `shutdown()`、`tryTerminate` 的 ONLY_ONE |
| `interruptWorkers`→`interruptIfStarted`（L774/L673）| **全部** worker，包括正在跑任务的 | `shutdownNow()` |

关键：`tryLock()` = `tryAcquire` 的 CAS 0→1（L654/L669）。Worker 在**跑任务前会 `w.lock()`**（runWorker L1131），
所以「抢不到锁」= 它正在执行任务 = 不是 idle。这就是 shutdown 能精确只叫醒空闲线程、不打扰在跑任务的原理。

### 2. 情况 A：worker 正空闲（阻塞在 `getTask`）—— shutdown 的典型路径

`getTask`（L1042）在 `workQueue.take()` / `poll(keepAlive)` 上被 park，interrupt 会让它抛 `InterruptedException`：

```java
try {
    Runnable r = timed ?
        workQueue.poll(keepAliveTime, ...) :
        workQueue.take();            // ← 阻塞点，被 interrupt 抛异常
    if (r != null) return r;
    timedOut = true;
} catch (InterruptedException retry) {   // L1074
    timedOut = false;                    // 吞掉异常，for(;;) 继续下一轮
}
```

注意：**异常在这里被直接吞掉、循环重试**，并不会让 worker 退出。worker 会不会真的走，取决于下一轮开头的状态复查（L1046-1053）：

```java
int c = ctl.get();
if (runStateAtLeast(c, SHUTDOWN)
    && (runStateAtLeast(c, STOP) || workQueue.isEmpty())) {
    decrementWorkerCount();     // 在这里扣 workerCount
    return null;                // 返回 null → 触发退出
}
```

- 若只是 interrupt 而池仍 RUNNING（比如误发/无关中断）→ 这个 if 不成立 → worker **继续回去 take，不会被移除**。所以「interrupt 让 worker 退出」必须搭配一次状态推进（SHUTDOWN/STOP）才成立。
- 若 `shutdown()` 已把状态推到 SHUTDOWN 且队列已空（或 STOP）→ `getTask` 返回 null。

回到 `runWorker`（L1130）：

```java
while (task != null || (task = getTask()) != null) { ... }   // getTask 返回 null → 循环结束
completedAbruptly = false;    // L1156，正常退出，非异常
...
finally { processWorkerExit(w, completedAbruptly); }   // L1158
```

### 3. 情况 B：worker 正在跑任务 —— shutdownNow 的路径

`shutdownNow` 用 `interruptIfStarted`（L673，不看锁、忙闲都中断）把 STOP 中断打到在跑任务的线程上。之后 runWorker 在每次取任务前有一段「停止态维持中断」的逻辑（L1136-1140）：

```java
if ((runStateAtLeast(ctl.get(), STOP) ||
     (Thread.interrupted() && runStateAtLeast(ctl.get(), STOP))) &&
    !wt.isInterrupted())
    wt.interrupt();
```

任务本身 (`task.run()`) 对这个中断的反应决定结局：

- **任务响应中断**（抛 `InterruptedException` 之类）→ 冒泡到 L1146 被 catch，调 `afterExecute` 后 `throw ex`（L1148）→ 跳出 while 循环，`completedAbruptly` 保持 `true` → 进 `processWorkerExit(w, true)`。
- **任务吞掉中断、照常跑完** → `finally` 里 `task=null` 解锁，回到循环顶再 `getTask()`；此时状态是 STOP，L1050 `runStateAtLeast(c, STOP)` 成立 → 返回 null → 正常退出（`completedAbruptly=false`）。

也就是说：**中断能不能「掐死」正在执行的任务，取决于任务自己响不响应**（源码 javadoc L1414 明说「no guarantees beyond best-effort… any task that fails to respond to interrupts may never terminate」）。TPE 无法强杀线程。

### 4. 真正从 workers 移除 + 计数的地方：`processWorkerExit`

不管 A 还是 B，跳出循环后都进这里（L997）：

```java
private void processWorkerExit(Worker w, boolean completedAbruptly) {
    if (completedAbruptly)               // 异常退出时 getTask 没来得及扣数
        decrementWorkerCount();          // L999 这里补扣
    mainLock.lock();
    try {
        completedTaskCount += w.completedTasks;
        workers.remove(w);               // ★ 唯一把 worker 移出集合的地方（L1005）
    } finally { mainLock.unlock(); }

    tryTerminate();                       // L1010 推动 SHUTDOWN/STOP → TIDYING → TERMINATED

    int c = ctl.get();
    if (runStateLessThan(c, STOP)) {      // 池还没 STOP，可能要补一个 worker
        if (!completedAbruptly) {
            int min = allowCoreThreadTimeOut ? 0 : corePoolSize;
            if (min == 0 && !workQueue.isEmpty()) min = 1;
            if (workerCountOf(c) >= min) return;   // 够数，不补
        }
        addWorker(null, false);           // L1021 补线，维持 core 或应对异常退出
    }
}
```

要点：

- **移除只此一处**：`workers.remove(w)`（L1005）在 `mainLock` 保护下执行，`addWorkerFailed` 的回滚（L976）是另一个「没成功启动就撤销」的移除，正常退休路径就是这里。
- **workerCount 谁扣**：正常退出（getTask 返回 null）时已在 getTask 里扣过（L1051 `decrementWorkerCount` / L1062 `compareAndDecrementWorkerCount`），所以 `completedAbruptly=false` 不再重复扣；异常退出没走到扣数那步，由本方法 L999 补扣。
- **补不补线**：池若还 `< STOP`（即 RUNNING/SHUTDOWN），且当前 worker 数低于 `min`（core，或开了 `allowCoreThreadTimeOut` 则为 0），或属异常退出，就 `addWorker(null, false)` 补一个新的空闲 worker。STOP 态则不补——反正要熄火。

一句话收束：**interrupt 只负责「叫醒/打断」，让 worker 跳出 `runWorker` 循环；退出后由 `processWorkerExit` 统一从 `workers` 移除、调整 `workerCount`、`tryTerminate` 并在需要时补线。**

## 七、池到 TERMINATED 后，worker 是游离漂浮还是已销毁？

先纠正一个隐含前提：Java 里**不存在「线程还在跑、但已与池脱钩、闲置待命」的中间态**。一个 `Thread` 只要 `run()` 没返回就是 RUNNING，一旦 `run()` 返回就进入 `Thread` 自己的 TERMINATED 并消亡——没有「已注销但保活待命」的第三种状态。TPE 的 worker 恰恰是「要么在循环里、要么就死」的设计。

### 1. 关键事实：worker 被「复用」靠的是留在循环里，不是脱离注册表保活

`runWorker`（L1123）里，线程想接下一个任务，是**继续在 `while (task != null || (task = getTask()) != null)` 循环里转**，此时它一直：

- 在 `workers` set 里（没被 remove）；
- 被 `workerCount` 计着数；
- 阻塞点在 `getTask` 的 `workQueue.take()`（L1070）。

所以「空闲 worker」不是「脱离池的漂浮线程」，而是**仍登记在册、卡在 take 上等活的活线程**。这正是 `shutdown` 时 `interruptIdleWorkers` 能遍历 `workers` 精确叫醒它们的前提（L803-805）。

### 2. 一旦被 remove，紧接着就是 run() 返回 → 线程死亡

`workers.remove(w)`（L1005）不是发生在「线程闲置时」，而是发生在 `processWorkerExit` 里——而 `processWorkerExit` 是 `runWorker` **`finally` 块的最后一件事**（L1157-1159）：

```java
final void runWorker(Worker w) {
    ...
    try {
        while (task != null || (task = getTask()) != null) { ... }   // 退出：getTask 返回 null 或抛异常
        completedAbruptly = false;
    } finally {
        processWorkerExit(w, completedAbruptly);   // ← 内含 workers.remove(w)
    }
    // runWorker 到此返回
}
// Worker.run() 只是 runWorker(this)（L641-643），它返回 == Thread.run() 返回
```

时序链条是单向的：

```
getTask() 返回 null（或任务抛异常）
  → while 循环结束
  → runWorker 进 finally 调 processWorkerExit
      → workers.remove(w)          // L1005，移出集合
      → tryTerminate()             // L1010
      → 判断是否补线
  → processWorkerExit 返回 → runWorker 返回 → Worker.run() 返回
  → 该 Thread 的 run() 执行完毕 → JVM 线程 TERMINATED，必然消亡
```

也就是说：**「从 workers 移除」是线程死亡的临门一脚，二者是紧邻的因果关系，不存在 remove 之后线程还留在别处漂浮。** remove 之后没有任何路径把这个 Thread 重新捞回池里——要接活只能靠 `addWorker` 造一个全新线程（L928 `new Worker` + L953 `container.start(t)`）。

### 3. 池到达 TERMINATED 的那一刻，最后那个 worker 处于什么状态

进入 TIDYING→TERMINATED 是 `tryTerminate()`（L715）干的，它的触发条件是 `workerCountOf(c) == 0`（L722），即**所有 worker 都已走完 `processWorkerExit` 里的 remove**。真正执行 `ctl.set(ctlOf(TERMINATED, 0))`（L734）的，正是**最后一个退出的 worker 线程自己**（它在自己的 `processWorkerExit → tryTerminate` 里抢到了 TIDYING 的 CAS）。

所以严格讲，存在一个极窄的窗口：

- 池状态已是 TERMINATED，`workers` set 已空，`workerCount` 已为 0；
- 但「最后一个 worker」这个线程对象，**它自己的 `run()` 还没返回**——它此刻正站在 `tryTerminate` 里执行 `terminated()` 钩子、`ctl.set(TERMINATED)`、`termination.signalAll()`、`container.close()`（L732-736）。

这不是「漂浮/游离态」，而是**正在执行死亡流程的最后一步**。它一返回，`processWorkerExit` 继续：

```java
int c = ctl.get();
if (runStateLessThan(c, STOP)) {   // L1013：此时 c=TERMINATED，>= STOP → 条件为假
    ...
    addWorker(null, false);        // 不会执行，不补线
}
```

因为池已 STOP/TERMINATED，**不会 `addWorker` 复活它**，函数直接返回，随后它的 `run()` 也返回、线程消亡。至此全部 worker 线程真正死干净：`workers` 空、`workerCount` 0、所有 Thread 均 TERMINATED。

### 4. 结论

- **不是游离漂浮态。** worker 线程要么「登记在册、卡在 getTask 循环里等活」，要么走完 `processWorkerExit` 后 `run()` 返回、彻底终止。Java 也没有「注销保活」的中间态可供它漂浮。
- **`workers.remove(w)` 是线程终止流程的一部分**（`runWorker` finally 的末尾），不是「先摘出去、线程还吊在外面」。
- 唯一可抠的细节：**池状态到 TERMINATED 的瞬间，最后一个 worker 线程的 `run()` 尚未返回**——它正替整个池执行 `terminated()`/置 TERMINATED/`container.close()`，属「正在收尾将死」，而非游离。等它返回后，所有线程与池状态才在物理上完全对齐。

> 副作用：正因 remove 即接近死亡，`getActiveCount()`/`getPoolSize()` 这类统计走的是 mainLock 下遍历 `workers`（`w.isLocked()`），它反映的是「仍在册的活线程」，与 OS 层面「线程是否已彻底回收」可能有纳秒级的收尾滞后，但绝不会把已 remove 的线程算进去。
