# JVM 演进笔记：17/21 对比 8/11 —— 内存布局 · GC · 特性

> 本文是 [`java-base.md`](./java-base.md) 系列的 JVM 侧补充：库和并发类读的是 `src.zip`，
> JVM 本体（HotSpot）读的是 JEP 与实测行为。三条主线——内存布局、GC、特性（语言 + 运行时）——
> 都按"8/11 旧基线 → 17/21 现状"对比展开，文末附 22~26 走向（截至 2026-09）。
>
> **版本口径**：本机 **temurin-17.0.19** 与 **temurin-21.0.11** 实测（`java -XX:+Xxx` 直接验证
> flag 存在性与默认行为，见文末清单）；8/11 口径依据对应版本官方文档与 JEP，本机无旧版
> **未逐行取证**；JDK 22 之后的 JEP 编号以 openjdk.org 各项目页为准。
>
> 定性：个人学习笔记，非框架文档。

## 0. 一页速览

| 维度 | 8 / 11 基线 | 17 / 21 现状 | 变化发生的版本 |
|---|---|---|---|
| 方法区 | Metaspace（已脱离 PermGen） | Metaspace + **弹性分配**（可归还） | 8（JEP 122）/ 16（JEP 387） |
| String 布局 | `char[]`，UTF-16 每字符 2 字节 | `byte[] + coder`，LATIN1 单字节 | 9（JEP 254） |
| 对象头 Mark Word | 偏向锁**默认开** | 偏向锁**已移除**（21 实测 flag 直接 Unrecognized） | 15 默认关（JEP 374）→ 21 前移除 |
| 默认 GC | 8：Parallel Scavenge + Parallel Old；11：G1 | G1；ZGC 分代可用（21 需显式开关，见 2.3） | 9（JEP 248） |
| GC 候选 | Parallel / CMS / G1 | Serial / Parallel / G1 / **ZGC / Shenandoah**（Epsilon 需解锁实验项，实测） | 11 实验 / 15 转正 |
| CMS | 存在 | **已移除** | 9 废弃（JEP 291）→ 14 移除（JEP 364） |
| GC 日志 | `-XX:+PrintGCDetails` 全家桶 | 统一 `-Xlog:gc`（**升级踩坑第一处**） | 9（JEP 158） |
| 线程栈 | 每平台线程预留 ~1MB 虚拟地址 | **虚拟线程**：栈以堆上段存续、可增长 | 21（JEP 444） |
| 堆外内存入口 | `sun.misc.Unsafe` 事实标准 | 官方替代 FFM API；Unsafe 内存方法 23 起废弃 | 21 三次预览（JEP 442）→ 22 转正 |
| 语言基线 | lambda / try-with-resources | + var、switch 表达式、文本块、record、instanceof/switch 模式匹配、sealed | 10~21 逐版本 |

三条主线的阅读顺序照旧有依赖：**布局是地基（对象在哪个区、长什么样）→ GC 是对布局的消费
（ barriers/RSet 都围着布局转）→ 特性里只有 Loom 反过来动了布局**（栈从线程私有搬到堆）。

---

## 一、内存布局

### 1.1 运行时区域：四个版本口径

```
JDK 7 及以前   堆 + PermGen（方法区，固定上限、Full GC 才卸载类、类元数据泄漏重灾区）
JDK 8         PermGen → Metaspace（JEP 122）：类元数据移到本地内存，默认无上限，
              GC 触发条件从"PermGen 满"变成"committed 达 -XX:MaxMetaspaceSize"
JDK 9~16      Metaspace 布局逐步改良：KMV、碎片问题缓解……
JDK 16        弹性 Metaspace（JEP 387）：VirtualSpaceStack 分配器，arena 连续分配，
              类卸载后可以真正 uncommit 归还内存——"Metaspace 只涨不落"的老印象到此作废
JDK 21        布局层面最大的变化不在堆内：虚拟线程（JEP 444）使"等待中的 RPC 调用"
              不再各占一个平台线程的栈（默认 -Xss 1MB 预留虚拟地址）；Continuation 的栈帧
              以堆上块存取，挂起时可回收、可增长——十万级并发等待的地址空间账直接抹平
```

本地内存这条线还要记一个 21 之后的方向：`sun.misc.Unsafe` 的内存访问方法
**JDK 23 废弃待移除（JEP 471）、24 起调用即告警（JEP 498，实测语义）**，官方替身是
FFM API（JDK 22 转正）。Netty/Jaws 依赖的直接内存与 `PlatformDependent` 反射路径暂不受影响，
但这是"堆外内存怎么拿"的十年之约，选型时值得留意。

### 1.2 堆内对象布局：Mark Word 与压缩指针

经典 64 位布局（开压缩指针，默认，堆 <32GB 前提）：

```
普通对象   mark word(8B) + klass pointer(4B) + 实例数据 …… 补齐到 8B 边界
数组       mark word(8B) + klass(4B) + length(4B) + 元素数据
```

8/11 与 17/21 在这张图上的**差异只有一个字节的归属和它的语义**：

- **偏向锁**（8/11 默认开）：Mark Word 里腾出 2bit 模式位 + 线程 ID 域，无竞争时加锁
  = CAS 写一次自己的线程 ID。**代价**在竞争与撤销：锁升级要全局 safepoint 重映射，
  撤销后该对象头永久"脱水"退回轻量锁形态。
- **JDK 15 默认关闭（JEP 374），随后直接移除**。移除理由写在 JEP 里：现代 Java 库
  （尤其并发库与 JIT 的锁消除）让偏向收益持续缩水，而它给对象头带来的复杂度
  挡住了后续布局演进。实测口径：17 上 `-XX:+UseBiasedLocking` 已打印
  "deprecated in version 15.0" 警告，**21 上直接 `Unrecognized VM option`**（文末清单 ①②）。
  网上大量 8/11 时代的"Mark Word 状态图"含偏向位，读 17/21 时先删掉那一档。
- **Compact Strings（9，JEP 254）**：`String.value` 从 `char[]` 换成 `byte[] + byte coder`
  （LATIN1/UTF16 双编码）。对布局的意义不只是"省一半"：字符串是堆内数量最大的对象类别，
  单字节化后缓存行装得下更多字符，序列化/注册中心 URL/attachment 这类字符串密集路径
  （Jaws 到处都是）在 9+ 上天然受益；`hashCode` 缓存与基于内容的比较逻辑随之改写。
- **方向**：对象头压缩（Project Lilliput）——mark word 与 klass pointer 合并为 8 字节，
  JDK 24 实验（JEP 450）、25 转正（JEP 519）、27 计划默认（JEP 534）。21 实测无此 flag
  （文末清单 ③），所以"12 字节头 + 对齐"仍是 17/21 的现实口径；前置的 64 位价值化
  Mark Word 在 24 落地。**记结论即可：头部瘦身不在你们的 17/21 上，别按 25+ 的图背。**

### 1.3 分配与超大对象（各 GC 的布局切分）

- **TLAB**：线程本地分配缓冲区，Eden 指针碰撞 + 每线程私有段，8~21 未变；小对象分配
  永远是这条最快路径。
- **Parallel/Serial**：连续 Eden + 两块 Survivor + Old，老年代整理用标记-压缩。
- **G1**：堆切成 Region（1~32MB，默认约 2048 个目标粒度），角色动态指派；
  **Humongous 对象**（>50% Region）直接占连续 Region——"大数组不进 TLAB、把 Region 切碎"
  是 G1 特有的碎片来源。
- **ZGC**：Region 化 + 分代后按 young/old 分堆段（见 2.3）；元数据与视图在本地内存。

---

## 二、GC

### 2.1 时间线（8 → 21，只记"发生即永久"的节点）

| 版本 | 事件 | 备注 |
|---|---|---|
| 8 | Parallel Scavenge + Parallel Old 默认；Metaspace 取代 PermGen | G1 可用但默认关 |
| 9 | **G1 成为默认**（JEP 248）；统一 JVM 日志 `-Xlog`（JEP 158）；CMS 废弃（JEP 291） | 日志参数从此换代 |
| 10 | G1 并行 Full GC（JEP 307）；容器感知（JEP 313） | 容器里 `-Xmx` 开始跟着 cgroup 走 |
| 11 | **ZGC 实验**（JEP 333）、Epsilon 实验（JEP 318）；JFR 开源（JEP 328）；Thread-Local Handshakes（JEP 312） | 11 上 ZGC 无分代、单视图 |
| 13 | ZGC 去实验 + **动态 uncommit**（JEP 351） | 空闲内存还给 OS |
| 14 | **CMS 移除**（JEP 364）；NUMA 感知 G1（JEP 345） | 8 时代"CMS 调优手册"整体作废 |
| 15 | ZGC（JEP 373）/ Shenandoah（JEP 379）**转正**；偏向锁默认关（JEP 374） | 低停顿三选一（G1/ZGC/Shenandoah）成型 |
| 16 | ZGC 并发处理线程栈（JEP 376）；弹性 Metaspace（JEP 387）；内部封装默认强约束（JEP 396） | 栈扫描不再 STW，停顿进入 <1ms 叙事 |
| 19 | **分代 ZGC 预览**（JEP 428） | 补齐分代假设 |
| 21 | **分代 ZGC 转正**（JEP 439）——但默认仍是单代，见 2.3 实测；虚拟线程（JEP 444） | LTS 基线 |
| 23 | 分代 ZGC 转默认（JEP 474）；Unsafe 内存方法废弃待移除（JEP 471） | （超出本机，官方页核对） |
| 24 | 非分代 ZGC 移除（JEP 490）；紧凑对象头实验（JEP 450）；分代 Shenandoah 实验（JEP 404）；Unsafe 调用告警（JEP 498） | 同上 |

停顿模型本身 8→21 只换了范式：**标记-整理**（Parallel old）、**标记-清除**（CMS，碎片
致命、remark 失败退串行）、**Region 增量的标记-复制**（G1）、**全并发标记-复制**（ZGC /
Shenandoah）。CMS 被删不是因为不好，而是它的两个接班人（G1/ZGC）把它的问题域全覆盖了。

### 2.2 跨区引用：三种 GC 三套账本

GC 增量/并发回收的核心难题是**"老年代指针指向新生代/被移动对象"怎么被记账**，
三种方案决定了三种 GC 的形态，也决定了 8→21 升级时行为差异的根因：

| | 屏障类型 | 记账结构 | 代价形态 |
|---|---|---|---|
| Parallel / CMS | **写屏障**（增量更新） | 卡表 Card Table | 扫描卡表时的 STW 阶段（CMS 的 remark） |
| G1 | **写屏障**（SATB 快照 + RSet 维护） | 每 Region 的 RSet（卡表派生） | 屏障两次改写 + RSet 内存/维护开销 |
| ZGC | **读屏障**（着色指针，M0/M1 位） | **无 RSet**——引用移动时按访问点修正 | 每次 load 引用过一道屏障；指针元数据占 2 bit |
| Shenandoah | 读写都有（Brooks 指针 → 14 后改读屏障路线） | Region 级 | 屏障 + 转发指针开销 |

G1 的日志实测里有 `CardTable entry size: 512` 一行（21 默认 G1，文末清单 ⑦）——卡表
仍然是 G1 的地基，8 到 21 没换范式，换的是 Region 策略、并行化、NUMA、预测模型这些
"消费方式"。ZGC 没有 RSet，这是它能把停顿和堆大小解耦（TB 级堆 <1~10ms）的结构性原因，
代价则是吞吐折损——**GC 选型本质是"把成本摊在停顿、吞吐还是内存上"的三选一**。

### 2.3 分代 ZGC：21 上必须实测的一条口径

JEP 439 在 21 把分代 ZGC 转正，但**默认没有打开**，23 才反转（JEP 474）。本机实测：

```
$ java -Xlog:gc+init -version                          # 默认 G1：无 ZGC 行，CardTable 512
$ java -XX:+UseZGC -Xlog:gc+init -version
  [info][gc,init] Using The Z Garbage Collector
  [info][gc,init] Using legacy single-generation mode   ← 21 默认 ZGC = 单代
$ java -XX:+UseZGC -XX:+ZGenerational -Xlog:gc+init -version
  （无上一行）                                          ← 分代需显式开启
```

为什么要分代：单代 ZGC 每轮周期处理**全部** Region，短命对象熬不过一轮就得跟着全堆搬迁，
弱分代假设（绝大多数对象朝生夕死）完全用不上；分代后 Young 周期只扫年轻集、复制量小、
频率高，吞吐与内存占用都向 G1 靠拢，同时保住 <1ms 停顿。**结论：在 21 上选 ZGC 就要
`-XX:+ZGenerational`；在 23+ 上这个 flag 反而是多余的，在 24+ 上单代模式已死。**
（顺带：21 的 Shenandoah 也仍是非分代，分代 Shenandoah 24 实验、25 转正。）

### 2.4 可观测性换代（升级必踩）

```
JDK 8   -XX:+PrintGCDetails -XX:+PrintGCDateStamps -Xloggc:file -XX:+UseGCLogFileRotation
JDK 9+  -Xlog:gc*:file=gc.log:time,uptime:filecount=10,filesize=10m   # JEP 158 统一日志框架
```

两套参数**互斥且旧参数直接报错**，容器化部署里这是 8→11 升级第一颗雷。11 的另一件大事是
**JFR 开源**（JEP 328）：长期在线的性能记录，21 上 `jfr` 命令行工具可直接读——
低开销剖析这件事从此不需要第三方 agent。

---

## 三、特性（语言 + 运行时）

### 3.1 8 → 11：这次升级拿到什么

语言：var（10，JEP 286）、lambda 参数 var（11）、接口私有方法（9）、try-with-resources
放宽（9）。库：模块系统（9，JEP 261）、JShell（9，JEP 166）、集合工厂
`List.of`（9）、`Map.Entry` 比较器、流增强 `takeWhile/dropWhile/iterate`（9）、
`CompletableFuture` 超时方法（9）、HTTP Client 标准化（11，JEP 321，支持 HTTP/2）、
String 新方法 `strip/repeat/isBlank/lines`（11）、`Optional.isEmpty`（11）。
运行时：紧凑字符串（9）、G1 默认（9）、容器感知（10，JEP 313）、AppCDS（10，JEP 310）、
ZGC/Epsilon（11）、JFR（11）、Nest-Based Access Control（11，JEP 181——反射与
invokedynamic 访问控制的地基改造）。

8 时代遗产被清点的部分：Java EE/Corba 模块移除（11）、`javax.xml.bind` 等掉出 JDK——
**8→11 的痛多半不在 JVM，在依赖清单**。

### 3.2 11 → 17：语言现代化 + 封装收紧

语言（预览→转正的接力，最终都在 17 之前收齐）：

```
switch 表达式      12/13 预览 → 14 转正（JEP 361）
文本块             13/14 预览 → 15 转正（JEP 378）      ← Jaws 的 // noinspection 旁注里已有文本块 JSON 用例
instanceof 模式    14/15 预览 → 16 转正（JEP 394）
record             14/15 预览 → 16 转正（JEP 395）
sealed 类          15/16 预览 → 17 转正
```

运行时/库：内部 API 默认强封装（16，JEP 396——**大量反射库从此需要 `--add-opens`**，
17 的 JEP 403 进一步收紧）、SecurityManager 废弃（17，JEP 411）、`Stream.toList`、
`RandomGenerator` 接口族、HexFormat、UTF-8 成为默认字符集（**18**，JEP 400——影响
序列化与日志编码兼容，跨 17/21 边界时留意）、Nashorn 移除（15，JEP 372）、
macOS/AArch64 移植（16/17）。GC 侧见 2.1 表（15 三 GC 转正、16 栈处理/弹性 Metaspace）。

### 3.3 17 → 21：Loom 落地

- **虚拟线程转正（JEP 444）**：调度单元从 OS 线程变成堆上对象，阻塞 = 卸载栈、让出载体
  线程。配套两条仍预览：结构化并发（JEP 453）、Scoped Values（JEP 446）。
  21 的经典遗留问题：`synchronized` 块内阻塞会 **pin 住载体线程**——24 的 JEP 491 才解决，
  17/21 上高并发路径建议 ReentrantLock 或避开长临界区。
- **模式匹配收官（JEP 440/441）**：`switch` 模式匹配 + record 解构转正，语言侧 8→21 的
  代差到此可以一句话概括：*类型即值（record）、值即类型（pattern）*。
- **Sequenced Collections（JEP 431）**：`getFirst/reversed()` 统一有序集合门面。
- **FFM API 三次预览（JEP 442）**、**Vector API 六次孵化（JEP 448）**：堆外与 SIMD 的
  官方化，22 起 FFM 转正。
- **String Templates（JEP 430）两次预览后于 23 撤回**——文本插值这条支线夭折，
  别在 21 项目里用它。
- 运维向：动态 attach agent 将受限（JEP 451）、Windows 32-bit 端口废弃（JEP 449）。

### 3.4 前瞻（22 → 26，截至 2026-09，官方页/JEP 号已核对）

分代 ZGC 默认（23）→ 非分代移除（24）；紧凑对象头实验（24）→ 转正（25）→ 拟默认（27）；
分代 Shenandoah（24 实验 → 25 转正）；SecurityManager 永久禁用（24，JEP 486）；
Leyden 启动加速路线：AOT 类加载链接（24，JEP 483）→ 26 的 AOT 对象缓存可与任意 GC
搭配（JEP 516，二手来源）；32-bit x86：21 废弃 Windows 32-bit（JEP 449）→ 24 整体废弃
（JEP 501）→ 移除倒计时。Valhalla 价值类仍是"下一场革命"的前置（24 的 64 位 Mark Word
即其铺路之作）。

---

## 四、Jaws 对应

- **虚拟线程 vs 全链路异步**：Jaws 的 FutureListener 异步链（`AbstractClient` 的
  callbackMap 协议）解决的是"IO 线程不被业务阻塞"；虚拟线程解决的是"业务侧等待不占
  OS 线程"。两者是同一问题的两种答案——**Jaws 不需要为此重写，但 sample/工具链和 Harbor
  客户端里"同步写法 + 高并发等待"的场景从此可以直接 `Thread.ofVirtual()`**；
  17/21 上注意 synchronized pinning（24 才修）。
- **G1 Humongous ↔ Netty 池化**：Jaws 传输层的大 payload 若走堆内大数组，在 G1 上是
  Region 碎片源；Netty `DEFAULT_DIRECT` 的池外分配天然绕开，这是"堆外 + 零拷贝"
  在 17/21 上的又一层理由。
- **Compact Strings ↔ 注册中心链路**：provider URL、attachment 键值、配置中心字符串
  密集对象在 9+ 上平均省一半——同一份注册表数据，8 与 21 的驻留内存不可直接对比。
- **JFR（11 开源）↔ 观测性**：`doc/observability.md` 的指标路线之外，JFR 是零依赖的
  进程内事件流（`jfr print --events jdk.GCPhasePause`），benchmark 归因 GC 噪声首选。
- **统一日志 ↔ benchmark 脚本**：`run-sample.sh`/benchmark 里任何 `-XX:+PrintGC*` 残留
  在 17/21 上都会让 JVM 起不来，参数必须换 `-Xlog:gc*` 口径。
- **偏向锁移除 ↔ 锁开销叙事**：网上"偏向锁降低无竞争 synchronized 开销"的文章全是
  8/11 口径，17/21 上无竞争加锁就是轻量锁 CAS 写 mark word——`java-base.md` ③ 站读的
  新版 AQS park 路径正是这套布局之上的机制。

## 五、验收问题

- [ ] Metaspace 的 GC 触发条件是什么？和 PermGen 时代"固定上限 + Full GC 才卸类"差在哪？
      弹性 Metaspace（16）改掉了"只 commit 不 uncommit"的哪一段？
- [ ] 画出 21 上的对象头（含数组），指出 8/11 口径里多出来的是哪几位；偏向锁三个状态
      （可偏向/已偏向/匿名）为什么它的撤销需要 safepoint，移除后 mark word 省了什么？
- [ ] Compact Strings 之后 `String.hashCode` 缓存、`equals` 逐字符比较各受什么影响？
      为什么说"省一半"不只是 `char→byte`？
- [ ] G1 的 RSet 谁写、谁读、什么时候维护？ZGC 为什么敢不要 RSet，读屏障 + 着色指针
      的 2bit 从哪来的（提示：结合 1.2 的 64 位地址与压缩指针阈值）？
- [ ] 实测复述：21 上 `-XX:+UseZGC` 日志里那行 "legacy single-generation mode" 什么版本
      消失、什么 flag 消除它、23/24 各发生什么？分代 ZGC 的吞吐收益从弱分代假设怎么推出来？
- [ ] 8→11 与 11→17 各踩一颗什么雷？（分别答：GC 日志参数互斥 / `--add-opens` 强封装）
      17→21 的 synchronized pinning 是什么版本解决的，代价是什么写法？
- [ ] 虚拟线程"栈在堆上"与 G1/ZGC 的堆回收如何互不干扰（提示：Continuation 的栈块由
      谁管理、活线程的栈为什么不能随便移动）？——这题答不出说明 1.1 和 2.2 还没打通。

---

## 附：已验证事实清单（2026-09-28）

本机 temurin-17.0.19 / 21.0.11 实测（命令 → 输出摘录）：

1. **21 上 `-XX:+UseBiasedLocking` → `Unrecognized VM option`**；17 上同参数打印
   "deprecated in version 15.0 and will likely be removed"。偏向锁时间线：15 默认关
   （JEP 374）→ 17 仍接受但告警 → 21 已移除。
2. **21 上 `-XX:+UseCompactObjectHeaders` → `Unrecognized VM option`**：紧凑对象头
   不在 21；24 实验（JEP 450）、25 转正（JEP 519）经 openjdk 项目页与多方来源核对。
3. **21 可用 GC 实测**：Serial/Parallel/G1/ZGC/Shenandoah 均可直接 `-XX:+UseXxxGC` 启用；
   Epsilon 需 `-XX:+UnlockExperimentalVMOptions`（21 上仍实验口径）。
4. **21 默认 ZGC = 单代**：`-XX:+UseZGC -Xlog:gc+init` 打印 "Using legacy single-generation
   mode"；加 `-XX:+ZGenerational` 后该行消失。JEP 439（21）转正但不默认，23 默认（JEP 474）、
   24 移除非分代（JEP 490）均经 openjdk 项目页核对。
5. **21 默认 GC = G1**：无 ZGC 初始化行、`gc+init` 打印 `CardTable entry size: 512`。
6. **JDK 21 官方特性清单**（openjdk.org/projects/jdk/21/ 逐条核对）：430/431/439/440/441/
   442/443/444/445/446/448/449/451/452/453——文中引用的 21 侧编号以此为准；JDK 23 页核对
   474/471，JDK 24 页核对 404/450/472/475/483/486/490/491/498/501。
7. **未逐行取证项**：8/11 的默认 GC 与 flag 行为按对应版本文档口径（8 = Parallel，
   9 = G1 默认）；22/25/26/27 的个别 JEP（519/521/534/516）来自二手来源与 OpenJDK 公告，
   升级引用前建议再对一次 openjdk.org 对应项目页。

> 与 [`java-base.md`](./java-base.md) 的分工：那边管 `java.base` 源码里看得见的东西
> （容器/同步器/线程池），本文管看不见的那台机器（布局/GC/flag）。AQS 重写版口径
> （17/21 已 go-dark）与本文 3.3 的 Loom 是同一批工作的两面，两文互链阅读。
