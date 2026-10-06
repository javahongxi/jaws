# ReentrantLock 源码详解（java-base ③ 站展开）

> 本文是 [`java-base.md`](java-base.md) ③ 站「AQS + CountDownLatch + ReentrantLock」中 **ReentrantLock 一支的展开笔记**（③ 站其余主题见 java-base.md 本尊、④⑤ 站及其 java-base-4/5 展开）。
> 源码取自本机 **Eclipse Temurin 21.0.11**（`$JAVA_HOME/lib/src.zip`）；行号为实测。
> 版本口径：go-dark 重写后的 AQS/ReentrantLock（JDK 17 起），旧讲义 SIGNAL/PROPAGATE 口径不适用。

## 一、本质之问："锁 = CAS"对一半

先看最小样本——公开的非阻塞 `tryLock()`（ReentrantLock.java L125-141）：

```java
final boolean tryLock() {
    Thread current = Thread.currentThread();
    int c = getState();                          // volatile 读（AQS L537 声明）
    if (c == 0) {
        if (compareAndSetState(0, 1)) {          // L129 ← 一切的起点
            setExclusiveOwnerThread(current);    // 赢 CAS 之后才登记主人
            return true;
        }
    } else if (getExclusiveOwnerThread() == current) {
        if (++c < 0)                             // overflow
            throw new Error("Maximum lock count exceeded");
        setState(c);
        return true;
    }
    return false;
}
```

无竞争拿锁最终都收缩成同一条机器指令（x86 `lock cmpxchg` / arm64 LL/SC），这个意义上"拿锁就是一个 CAS"成立——`compareAndSetState`（AQS L568）就是 `U.compareAndSetInt(this, STATE, expect, update)` 一层壳，与 `AtomicInteger.compareAndSet` 的 `U.compareAndSetInt(this, VALUE, …)` **同一件 intrinsic**。但 CAS 只买到"原子性"一件货，锁的定义性特征是另外三样，CAS 一个都给不了：

- **阻塞语义**——拿不到的人要有地方睡、睡错了要能被准确叫醒（CLH + park，见第四节）；
- **所有权**——`getExclusiveOwnerThread()` 那格：重入、unlock 者校验、`hasQueuedPredecessors` 的 `!= current`，全消费"锁有主人"这个概念，AtomicInteger 的世界里没有"我的值"；
- **交接**——unlock 不只是写回 0，还要 `signalNext` 点名：值的归还同时是一次责任的转移。

精确表述：**ReentrantLock = 一枚 CAS + 一套 CAS 失败后的协议**。只搭 CAS 的"锁"，功能上就是 `tryLock`——一把会成功的乐观尝试，与 AtomicInteger 同一物种。

### 1.1 CAS 的边界：赢 CAS 与登记 owner 之间有个缝

L129→L131 的程序序里，赢家已"持锁"而 owner 尚是旧值/null——这不是 bug，是 **CAS 只能原子地动一个字段、跨字段不变式它管不了**的先天局限。正确性靠两件事兜住：

1. **CAS 本身是 linearization point**——锁的"归属切换"发生在 0→1 那一瞬，owner 只是这个瞬间的登记副作用；
2. **state 是 volatile**（AQS L537），`compareAndSetState` javadoc 自报 *"memory semantics of a volatile read and write"*——owner 写在 CAS 之前（程序序），下一个读到 `state == 1` 的人经 happens-before 必能看到配套 owner。

这个模式是通用武器：**单字段 CAS 当门闩、旁挂字段做登记、靠内存序追平**。AQS 的 head/tail、CHM 的 sizeControl/counterCells 都是它的变体；反过来，需要"两个字段同时变"的硬不变式时，CAS 帮不了你——那才是真锁、真事务协议的活。

### 1.2 四份 tryLock 系 CAS 的经济账（行号实测）

| 位置 | 守卫 | 重入 | 为什么这样设计 |
|---|---|---|---|
| `tryLock()` L126-129 | `c == 0` 预判 | 有 | API 常拿锁被占当答案，volatile 读挡掉注定失败的原子指令（arm 上 CAS fail 不便宜：LL/SC 重试环+独占监视器） |
| 非公平 `initialTryLock` L225 | **无守卫裸 CAS**，注释 *"first attempt is unguarded"* | 有 | 主场景无竞争，CAS 一发即中，预读纯属多一次 load；赌的是赢 |
| 非公平 `tryAcquire` L242 | `getState() == 0` | **无**（注释 *"non-reentrant cases after initialTryLock prescreen"*） | 处在"锁大概率被他人持有"的唤醒重试语境，省 fail 路径；重入已被 prescreen 截流，模板只答"空枪能否上膛" |
| 公平 `tryAcquire` L279-281 | `!hasQueuedPredecessors()` | 无 | 见下文谓词分工 |

公平锁 prescreen 与模板的谓词**宽窄不同是必须的**：prescreen 问"队列全空吗"（`!hasQueuedThreads()`，L263，tail 一眼）；模板问"头名是不是我"（L1318-1325：`head.next.waiter != current`）。因为同一个模板还要伺候**入队后被唤醒的自己**——排队者醒来重试时队列当然非空，若只认"全空"，队头永远叫不应自己，公平锁会死锁在自己的规则上。`!= current` 一词同时放行两种身份："没人排队的新人"与"排到队头的旧人"。

## 二、lock()：一次分诊，快慢两路

```java
final void lock() {                     // Sync L151-154
    if (!initialTryLock())
        acquire(1);
}
```

- **快路径** `initialTryLock()`：非公平 = 裸 CAS（上表）+ owner 重入分支（`c < 0` 溢出检查抛 Error——重入栈深有硬上限，这是正经不变式不是装饰）；公平 = 重入同款 + "全空才抢" gate。**重入判断住在快路径**是 21 版结构事实：owner 重入必然在此办结，落到慢路径的按构造是新人。罕见竞态兜底：owner 在快路径重入 CAS 的瞬间恰逢 release+被抢，会**带着锁去 acquire 排队**，排到再叠一层——正确性无恙，只多绕一圈。
- **慢路径** `acquire(1)` 不是"重复检查"——`tryAcquire` 那发是**循环的常驻弹药**（见第四节 `first || pred == null`），只是第一次路过时长得像重复。先探再睡是排队协议标配：跳号直队的代价是一次 park/unpark（微秒级，贵两个数量级）；21 的实现连循环内多余重试都忌惮（`first/pred` 判位就是防不该试的时候再试），门口这一发必是性价比最高的一个字节。

## 三、unlock()：出口哲学，释放的重头戏是点名

```java
public void unlock() { sync.release(1); }

public final boolean release(int arg) {      // AQS L1092-1097
    if (tryRelease(arg)) {
        signalNext(head);
        return true;
    }
    return false;
}

protected final boolean tryRelease(int releases) {   // Sync L172-181
    int c = getState() - releases;
    if (getExclusiveOwnerThread() != Thread.currentThread())
        throw new IllegalMonitorStateException();    // 不配对在这里现形
    boolean free = (c == 0);
    if (free) setExclusiveOwnerThread(null);
    setState(c);
    return free;
}
```

- 分工仍是模板方法两拍：**tryRelease 算账**（减、验主持者、归零才清 owner），**release 交接**（点名队头）。
- **返回值语义盯住**：重入没放完时 `release` 返回 **false**——不是"解锁失败"，是"**锁还没自由**"（tryRelease 返回的是 `free`）。
- `signalNext`（L626-634）三行是**交棒**的全部：`status != 0` 才点（没亮 WAITING 牌的人不许点名——Dekker 握手）、`getAndUnsetStatus(WAITING)` 原子认领（清得动人归我，防双 unpark）、`unpark(s.waiter)`。**公平/非公平在出口零差异**——抢锁才需要策略，交棒永远 FIFO。
- 与 synchronized 的镜像：monitor 的 enter/exit 是编译器隐式配对（monitorenter/monitorexit+异常表兜底），ReentrantLock 把配对义务整个交还给你——换来可限时、可中断、可观测的显式控制。误用形态也相反：synchronized 重入计数是机器暗账，ReentrantLock 漏 unlock = 锁永不自由 = 全队列吊死，所以纪律是 **`lock()` 与 `try{}finally{}` 之间不能有任何语句**（unlock 进 finally 天经地义，lock 出 try 才是对的——lock 和 finally 之间崩掉，锁就永远没人解）。

## 四、acquire 主循环：七个岔口（`unlock` 简单，因为难处全甩给了它）

六参 `acquire(Node node, int arg, boolean shared, boolean interruptible, boolean timed, long time)`（L704-805），注释自述 *"Main acquire method, invoked by all exported acquire methods"*——lock / lockInterruptibly / tryLock(t) / acquireShared / Condition 的 reacquire 五种进入方式共享这一套交接协议。两个坐标贯穿全程：`node`（我建了吗）、`pred/first`（我在队里的位置），八分支 dispatch 实为一条成长线：

```
pred==null 圈1..n：门外汉 → 试抢 → 建节点 → 挂尾 →（每圈再试一枪）
pred!=null 圈n+1..：排队者 → 醒着自旋 / 置 WAITING / park → 被点名 → first 胜者 → 换 head 出厂
```

1. **前置体检**（L730-738）：`pred.status < 0 → cleanQueue()`（前驱是取消的空壳，去修链）；`pred.prev == null → onSpinWait()`（前驱刚 CAS 上 tail、`t.next` 还没焊——两拍发布的缝，等它闭）。双向无锁链表里 prev/next 永远不可能同时原子写好，这是整张图上唯一的"链不可信"保护区；`cleanQueue`（L815-848）从 tail 反向三元组游标扫描，半发布时旁观者 *"help finish"* 替它把 next 焊上——帮助式并发协议。
2. **试枪资格**（L740-749）：`if (first || pred == null)` 才发 `tryAcquire`/`tryAcquireShared`——门外汉和队头共享这一发（上节谓词分工的循环侧证据）；异常先 `cancelAcquire` 再抛，链上不留烂账。
3. **胜者交接**（L752-761）：`first` 且抢到 → `node.prev=null; head=node; pred.next=null; waiter=null` 四句摘干节点，它从此以 head 身份活着。`node` 参数的存在理由：循环要把**具体哪个节点**提拔成 head——Condition `reacquire`（L665）正是带着旧 node 走同一条主脉。
4. **建队/建节点/挂尾**（L764-778）：`tail==null → tryInitializeHead()`（冷启动只造一次 dummy head）；`node==null → new Exclusive/SharedNode`（分配 OOM → `acquireOnOOME`，见 §六）；`pred==null` 挂尾走**两拍发布**——`setPrevRelaxed(t)`（注释 *avoid unnecessary fence*：prev 此刻私有，裸写免费）→ `casTail` 赢后 `t.next = node` 才公开。
5. **还手自旋**（L779-781）：`first && spins != 0` → 烧 `Thread.onSpinWait()` 再睡。`spins/postSpins` 在 park 出口按 `(postSpins << 1) | 1` 指数补给（1,3,7…byte 封顶 255）——刚被点名就躺平回笼，等于把 CPU 白让给 barger，专治"点名后装死"。
6. **亮牌**（L782-783）：park 前 `status = WAITING`——与 `signalNext` 的 `status != 0` 守卫配成握手；置完回圈重查全链才肯睡（Dekker 的 acquire 侧原文）。
7. **park 出口清算**（L784-798）：`clearStatus()` 消费被点名的凭证 → `interrupted |= Thread.interrupted()` **只累计不清偿** → `interruptible` 才 break；超时版每圈补差 `time - nanoTime`（同 STPE awaitNanos 写法）。所有 break 汇成同一撤退出口 `cancelAcquire`：摘链、责任转给后继、该抛 IE 抛 IE、该回 0 回 0。

一句话收束：`unlock` 是 O(1) 单向动作——state 减一、给已承诺的继承人递个 sticky permit 了事；`acquire` 是多边协议现场，全 JDK 最讲究的循环之一。**`signalNext` 简单，是因为所有难处都在对面。**

## 五、@ReservedStackAccess：交接不许烂尾

`tryLock/lock/unlock` 顶上的这个注解（jdk 内部注解 @since 9）不是文档，是给 HotSpot 的运行时命令：j.u.c 四家共 21 处（实测 ReentrantLock 5 / RRW 6 / StampedLock 8 / VirtualThread 2）。机制：每线程栈底预留一段（`-XX:StackReservedPageSize`，默认 32KB），日常禁入，调用链含此注解方法且栈将尽时放行进区执行完，出去后补一个迟到的 SOE（javadoc 连这条都写明可自由裁量）。它保护的是 `unlock()` 站在 finally 里栈却将尽的瞬间——SOE 一抛锁永不自由，全队列吊死。白名单强制：仅 boot loader 的类标了才算数；纯提示：VM 可忽略；防不了递归失控：32KB 只够再撑几层。

## 六、AtomicInteger 和 AQS 都拿 Unsafe——那到底什么区别？

先纠正一个常见误记（含本文早前口头稿）：**AQS 21 也没用 VarHandle**，且把理由写进了类 javadoc（L426-428）：

> *"We use jdk.internal Unsafe versions of atomic access methods **rather than VarHandles to avoid potential VM bootstrap issues**."*

两边用的还是**同一个** `jdk.internal.misc.Unsafe`（AtomicInteger L41/L62、AQS L44/L1974，皆 `Unsafe.getUnsafe()` 单例）。所以问题不是"谁的 CAS 更高级"——落地是同一件 intrinsic——而是**为什么这层干脆不用公开的 VarHandle**。"cyclic startup dependencies" 的实体抓到了，环有两圈：

- **正圈（AtomicInteger L59-60 的"cyclic"）**：`AtomicInteger` → VarHandle → `java.lang.invoke` 机制 → **`MutableCallSite.java` L283 正 `new AtomicInteger()`**——换过去就是类初始化环，启动期直接死锁。
- **反圈（AQS L427 的"bootstrap"）**：AQS/j.u.c 原子类是 VM 启动最早期就要能动的底层，若 init 链再拽上整包 `java.lang.invoke`，任何一环未就绪都会把 boot 卡死。

真正的区别在三张牌上：

1. **谁在"调用"Unsafe**：`U.compareAndSetInt` 不是终点——JVM 把它登记成 intrinsic（C++ 直发 `lock cmpxchg`，Java 帧整个消失），`U.putInt/getInt` 按字段声明（volatile 与否）自动升降内存序。AtomicInteger 表面是"方法调用"，实际机器码与 VarHandle 版同形。
2. **表达能力不同**：VarHandle 把内存序做成菜单（plain/opaque/release-acquire/volatile 全档 + fence），Unsafe 用"字段声明"定死——**AQS 21 的折中方案**：声明 `private volatile int state`（L537），想要弱序的 `waiter/prev/next` 字段声明成普通 volatile 之外再按需用 `putReference/getIntOpaque` 这类 op。所以 AQS 的字段访问表（类 javadoc L421-426）：head/tail/state 全 volatile+CAS；node 的 status/prev/next 可点名时 volatile、平时弱模式；waiter 永远 plain（"夹在其他原子访问之间"）。
3. **用户资格不同**：`jdk.internal.misc.Unsafe` 只有启动器加载的类能 `getUnsafe()`；**应用代码学 AQS 时正确姿势反而是 VarHandle**——公开、跨版本稳定、语义显式。j.u.c 原子类是被启动期循环绑架的"平民"，被迫用特权工具；我们抄模式时有特权可用，别学错对象。

## 七、衍生方向导航

- **ConditionObject（栏协议：await/signal 五段式与两栏交接）**——精读在 java-base.md ③ 站对话展开 + 清单第 9 条；主脉络：`enableWait` 先入栏后放锁、`doSignal` 清 COND 认领 + `enqueue` 搬家、唤醒永远在 `release → signalNext(head)`。
- **重入的实现**——owner 比对 + state 高窄计数（`c < 0` 溢出检查），见 §二；对比 monitor 的重入计数是 markWord 里的暗账。
- **锁的派生家族**——`ReentrantReadWriteLock`（state 高低 16 位分记读写，判据见本文 §八问 5）→ `StampedLock`（JDK 8，非重入、乐观读、单栏无 Condition，速度换能力档）；`CountDownLatch/Semaphore` 走同站共享模式路线。
- **与 synchronized 对照**——见 §三末镜像段；锁升级叙事在 JDK 15/18 后已改写（偏向锁删除、轻量级随压缩 klass 调整），java-base.md 清单第 9 条与简历第 1 条口径一致。

## 八、锁选型判据：什么等待拓扑必须上 ReentrantLock

synchronized vs ReentrantLock 的选择点自 JDK 6 起不在性能（偏向锁 JDK 15 已废、锁消除两家用谁都一样），只在**表达能力**：synchronized 是语言原语，只能表达"获取—执行—必然释放"；ReentrantLock 把这三步拆成方法，拆开的每一步都是能力、也都是责任。决策程序五问，**任何一问答案是"要"，synchronized 出局**：

1. **限时抢锁？**（`tryLock(t, unit)`）例：NettyRemotingClient 的 channelTables 维护，`lockChannelTables.tryLock(3000ms)`（L104 常量、L429/L474/L705 使用）——抢不到本轮放弃，不让路由刷新堵死调用线程。synchronized 无法表达"等但等不过 X"。
2. **可中断地抢锁？**（`lockInterruptibly()`）关停序列里等待中的线程要能被 interrupt 召回。注意 `Object.wait` 自带可中断，所以"可中断地**等待条件**"≠"可中断地**获取锁**"——后者只有显式锁给。
3. **公平？**（`new ReentrantLock(true)`）严格 FIFO 防 barging 饥饿。synchronized 永远是悲观 barging 模型，不可配置。
4. **一把锁几路等待？**（多 Condition）严格意义只有 **LinkedBlockingQueue** 是此判据的真样本：`notEmpty`（L160，挂 takeLock）+ `notFull`（L167，挂 putLock）——**不同谓词的等待者分住不同条件队列，signal 各点各的人**。对照 DWQ：只有一枚 `available`（L953），leader 定时 awaitNanos / follower 不定时 await / offer signal 是**一个 Condition、三种参与方式**（java-base-5 §二），点名精度靠 leader-follower 协议限制等待集，不靠多队列。synchronized 的监视器只有一条隐式等待集——LBQ 若用 synchronized 写，两类谓词线程混住一栏，只能 `notifyAll` 惊群后全员重筛，且无法针对"哪路人"点名。另记防坑：JDK 21 的 ReentrantReadWriteLock 内部已无 notEmpty/notInterested/noWaiters 条件组（网上多条件例证多已过时），判据级说法须先 grep 当前版本。
5. **读写分离？**（ReadWriteLock 族）synchronized 连变体都没有；顺带记一笔专属能力：写锁内的**锁降级**（持写锁拿读锁再放写锁），StampedLock 一族又反过来不提供可重入。

**辨析："多路等待"是不是就等价于"多个等待队列"？**——在数据结构层面字面成立：每个 `ConditionObject` 自带一条条件队列（`firstWaiter/lastWaiter` + `ConditionNode.nextWaiter` 串的 FIFO，AQS L1520-1522，入队 `addConditionWaiter` L1602-1607），一把锁 N 枚 Condition = **N 条条件队列 + 1 条主同步队列**。synchronized 的 monitor 是"每对象一条 wait set"，ReentrantLock 相当于把"每锁一栏"升级成"每条件一栏"——判据 4 的全部机制就这句。但三点限定，不然这个等价会误导：

- **"路"数的是谓词类别，不是等待人数**：LBQ 里 50 个 producer 堵 notFull、3 个 consumer 堵 notEmpty——人数 53，路数 2；
- **主同步队列永远只有一条**：`signal → doSignal`（清 COND 认领 + `enqueue` 搬进主队列）之后大家回同一条 CLH 抢锁——**分栏分的是"谁在等哪个事件"，不分"谁有资格抢锁"**，别把条件队列理解成平行世界；
- **分栏不是精确点名的唯一手段**：DWQ 单栏照样精确点名——leader-follower 用**协议**（栏内同时只有一位定时者、身份记在 `leader` 字段）实现"点名"。完整表述：**多 Condition = 结构性分栏（谓词天然隔离），单 Condition + 协议约束 = 语义性分栏（同栏里靠角色/票据控制谁有资格等）**。选哪条看等待者所候的**事件是否异类**：异类事件（非空 vs 非满）值得开第二栏；同类事件只是时限不同（队首变化，leader 定时/follower 不定时）用协议裁一下即可。Go 的 `sync.Cond` 与 Java monitor 同为单栏，多谓词只能靠外部协议——与 DWQ 思路同源；栏的有无，决定你是在"用能力"还是在"发明能力"。

五问全"不要"→ **synchronized 更优**：释放责任编译进结构（不可能忘 unlock/泄漏/乱序），逃逸分析下整锁可消除。RocketMQ 的正面样本 `ManyPullRequest`（longpolling L23-36）：synchronized 方法包 ArrayList，其中 `cloneListAndClear` 的"克隆+清空"必须整体原子——**这正是"一把互斥锁罩住 indivisible 状态转移"的教科书形状**，任何无锁容器都组合不出这个原子性。

（`lock()` 出 try 的配对纪律已并入 §三末镜像段，不重复。）

## 验收问题（读完本文必须能答）

- [ ] `tryLock()` 的 `setExclusiveOwnerThread` 在 CAS 成功之后才写，这个缝隙为什么安全？线性化点和 happens-before 各管哪半？
- [ ] 公平锁的 `tryAcquire` 用 `head.next.waiter != current` 而不是 `tail == null`，如果反过来会死锁在谁手里？
- [ ] `release()` 重入未放完时返回 false，false 的准确语义是什么？
- [ ] acquire 主循环里 `first && spins != 0` 烧 onSpinWait 是给谁防的漏？`spins` 从哪来、封顶多少？
- [ ] 两拍发布（setPrevRelaxed → casTail → t.next=）中间，旁观者从哪些分支感知并补全？（说出至少两个：岔口 1 体检、cleanQueue help finish、hasQueuedPredecessors 的重查路径）
- [ ] AtomicInteger 为什么不用 VarHandle（说出环的两圈各是什么）？应用代码为什么反而该用 VarHandle？
- [ ] `signalNext` 为什么必须检查 `s.status != 0`？它与 park 前的 `status = WAITING` 合成什么协议？
