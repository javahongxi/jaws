# JVM 实战笔记：GC 日志定位 Full GC —— 触发原因 · 回收效果 · 排查动线 · 工程化监控

> 本文是 [`jvm-1.md`](jvm-1.md)（JVM 演进笔记）的实战侧展开：那边管"布局/GC/flag 怎么演进"，
> 本文管"线上真出了 Full GC，怎么拿 GC 日志当第一手证据定位"。触发原因的机理（为什么老年代会满、
> Metaspace 何时报警）回指 jvm-1.md 对应章节，本文只展开**日志口径与排查动线**（截至 2026-11）。
>
> **版本口径**：延续 jvm-1.md——8/11 与 17/21 两套日志格式分开给；CMS 相关口径是 8 时代遗产
> （14 已移除，JEP 364），读新日志时先删掉这一档。
>
> 定性：个人学习笔记，非框架文档。

## 0. 结论先行

GC 日志是定位 Full GC 原因的第一手证据，但**通常只能定位到"触发类型"，不能直接告诉你是哪段代码、
哪个对象导致的**。它能回答"谁触发的、回收了多少、花了多久"；具体到对象和代码，要靠堆快照
（jmap/MAT）+ 在线诊断（Arthas/JFR）接着往下挖。

## 一、GC 日志里最该看什么：触发原因字段

重点看 Full GC 那一行里的**触发原因**，也就是括号里的内容。统一日志（9+，JEP 158）下的典型形态：

```
[gc] GC(42) Pause Full (Metadata GC Threshold)  # 括号内即触发原因
[gc] GC(42) Pause Full (G1 Compaction Pause)     # G1 的 Full GC 自带这个名字
```

| GC 日志关键字 | 常见含义 | 排查方向 | 机理回指 |
|---|---|---|---|
| `Allocation Failure` | 新生代分配失败，晋升时老年代放不下 | 看晋升速率、新生代/老年代比例、是否有大对象 | jvm-1.md 1.3 分配路径 |
| `Ergonomics` | JVM 自适应策略触发（Parallel 系） | 可能是堆大小、分代比例、GC 参数不合理 | jvm-1.md 2.1 时间线 |
| `Metadata GC Threshold` | 元空间达到 committed 上限 | 检查动态代理、CGLIB、反射、热部署、类加载泄漏 | jvm-1.md 1.1（Metaspace 取代 PermGen） |
| `System.gc()` | 显式调用 | 查代码或第三方库是否主动调用；21 上 `-XX:+ExplicitGCInvokesConcurrent` 可降级为并发回收 | — |
| `Promotion Failed` / `Tenure Failure` | 晋升失败：老年代没有**连续**空间容纳存活对象 | 老年代碎片、对象晋升过快 | jvm-1.md 2.1（标记-清除碎片） |
| `Concurrent Mode Failure` | **CMS 专属**：并发回收追不上分配速度，退化串行 Full GC | CMS 下老年代预留空间不足（`-XX:CMSInitiatingOccupancyFraction`） | jvm-1.md 2.1（CMS 14 已移除，新日志无此档） |
| `Pause Full (G1 Compaction Pause)` | G1 兜底 Full GC：evacuation 失败或巨型对象分配不出空间 | 巨型对象（>50% Region）、Region 碎片、分配压力 | jvm-1.md 1.3（Humongous） |
| `G1 Humongous Allocation` | 巨型对象分配触发的回收（不一定是 Full） | 大数组/大 payload 直进老年代，检查业务侧大对象来源 | jvm-1.md 四（Netty 池化天然绕开） |
| `Heap Dump Initiated GC` | `jmap -dump:live` / `jcmd GC.heap_dump` 触发 | 是排查动作本身触发的，不算故障 | 本文三 |

口径提醒：同一事件在不同收集器、不同大版本下**措辞不一样**（8 的 Parallel 打
`Pause Full (Ergonomics)`，G1 在 8 与 17/21 的行格式也不同），跨版本对比日志前先对一下
`-Xlog:gc+phases=debug` 能给出的阶段细节，别拿 8 时代的截图去套 21 的输出。

## 二、光看触发原因不够：还要看回收效果

触发原因回答"为什么触发"，回收前后对比回答"**GC 有没有用**"——这决定排查方向完全不同：

- Full GC 前老年代占用很高，GC 后**明显下降**：内存压力大、对象晋升快、参数不合理——调参 + 查分配热点。
- Full GC 后老年代**几乎不下降**，且水位持续抬高：高度怀疑内存泄漏——缓存只增不减、静态集合持有、
  ThreadLocal 未清理、类加载器泄漏（配合 `Metadata GC Threshold` 出现时优先查最后一条）。

判断动线：看单次回收量（`Old: X->Y`）只是起点，真正的信号是**多次 Full GC 后 Y 的抬升趋势**——
监控里画"每次 Full GC 后老年代占用"的折线，比盯任何一条日志都可靠。

## 三、推荐排查顺序

1. **先看 GC 日志触发原因**：确定是老年代不足、元空间不足、显式调用，还是并发回收失败（8 时代 CMS）。
2. **看 GC 前后内存变化**：重点关注 Eden、Survivor、Old Gen、Metaspace 各自的回收量。
3. **看频率和趋势**：Full GC 越来越密、老年代持续上涨，通常比单次 Full GC 更危险。
4. **必要时 dump 堆**：`jmap -dump:live,format=b,file=heap.hprof <pid>` 抓堆快照（live 会先触发一次
   Full GC，生产上慎来第二次；不带 live 可拿死亡对象现场），MAT / JProfiler 看支配树与 GC Roots 引用链。
5. **结合 Arthas / 监控工具**：`heapdump`、`dashboard`、`vmtool --action getInstances` 在线看对象实例数、
   线程、GC 趋势，避免频繁 dump 影响业务。零依赖路线用 JFR（11 开源，JEP 328）：
   `jfr print --events jdk.GCHeapSummary,jdk.GarbageCollection` 直接从事件里拿回收前后堆占用。

## 四、日志配置建议（两代参数互斥，勿混用）

JDK 8（旧 flag 全家桶）：

```bash
-Xloggc:/path/gc.log \
-XX:+PrintGCDetails \
-XX:+PrintGCDateStamps \
-XX:+UseGCLogFileRotation \
-XX:NumberOfGCLogFiles=5 \
-XX:GCLogFileSize=20m
```

JDK 9+（统一日志框架，JEP 158；11/17/21 通用）：

```bash
-Xlog:gc*:file=/path/gc.log:time:filecount=5,filesize=20m
```

两套参数**互斥且旧参数在新 JDK 上直接报错**（jvm-1.md 2.4 的"升级第一颗雷"，容器化部署里最容易踩）；
`run-sample.sh`/benchmark 脚本里任何 `-XX:+PrintGC*` 残留，在 17/21 上会让 JVM 起不来。

## 五、工程化：采集 → 消费 → GC 监控

前面的动线都是"出了事再查"，这一步可以前置成常态监控：**用日志采集组件把 GC 日志收上来，
消费时解析成结构化事件，就能给服务提供 GC 维度的监控**——不依赖每实例部署 agent，与
"指标拉取"（`../observability.md` 的路线，含 JVM 内存使用率类告警）形成互补：指标是水位采样，
GC 日志每次回收都是带原因字段的完整事件。

流水线的四段：

1. **采集**：Beats / Fluent Bit / Filebeat / Promtail 之类组件 tail 住 `-Xlog:gc*:file=gc.log`
   的输出文件；容器化场景也可以直接 stdout 采集，但注意 GC 行会混进应用日志流，建议独立
   stream/tag 分流；落盘 + 滚动（`filecount/filesize`，见本文四）保证采集端追得上。
2. **传输**：消息总线（典型如 Kafka）解耦生产与消费，采集端断链不回压业务进程。
3. **消费与解析**：消费者把非结构化日志行解析成结构化事件——正则匹配 `GC\(N\) Pause (Young|Full|Mixed)
   \(原因\) ... ms` 提取收集器类型、GC 类型、触发原因、回收前后各区占用、停顿时长；轻则自研消费者，
   重则 Flink 类流引擎，按服务×实例聚合。
4. **存储与告警**：时序库/日志库聚合出大盘：GC 频率、停顿时长分布（P99）、回收前后水位趋势、
   Full GC 触发原因占比；告警规则直接消费本文一/二的结论，如"Full GC 后老年代水位连续抬升"
   的 leak 趋势告警——这类**按事件配对**的规则用周期采样的指标做不了，只有逐条消费 GC 日志才拿得到。

口径提醒两条：统一日志的日期格式用 `-Xlog:gc*:...::time,uptime` 之类装饰器固定下来，
否则解析正则跟着 JDK 小版本漂；各收集器行格式不同（本文一的"措辞不一样"段），解析器要按
`gc+init` 行识别收集器再分发。零依赖的平替路线仍是 JFR（本文三第 5 步），事件天然结构化、
免正则，但没有现成日志链路直观，两条路线按基础设施选型。

## 六、验收问题

- [ ] 拿到一行 `Pause Full (G1 Compaction Pause)`，GC 日志能回答到哪一层？哪两个问题它回答不了、
      分别要靠什么工具接着查？
- [ ] `Promotion Failed` 和 `Concurrent Mode Failure` 分别发生在哪种收集器上？后者为什么在 17/21
      的日志里永远不会出现（提示：jvm-1.md 2.1，哪个 JEP 移除的）？
- [ ] Full GC 后老年代只降 5% 与降 60%，分别指向什么排查方向？为什么"单次占用高"不如
      "回收后水位持续抬升"更能说明泄漏？
- [ ] `jmap -dump:live` 为什么会额外触发一次 Full GC？生产上想拿"包含死亡对象"的现场该怎么 dump？
- [ ] 8→21 升级后 GC 日志读不出内容的第一颗雷是什么？新旧两套参数为什么不能同时挂？
- [ ] GC 日志监控流水线与指标拉取式监控（JVM 内存使用率告警）互补在哪？"Full GC 后水位持续抬升"
      这类告警为什么只有逐条消费 GC 日志才做得了？

---

## 附：与 jvm-1.md 的口径衔接

- CMS / `PrintGCDetails` 时代的资料全是 8/11 口径：CMS 9 废弃（JEP 291）→ 14 移除（JEP 364），
  17/21 上"老年代并发回收失败"的对应叙事是 G1 evacuation failure / Full GC 兜底。
- Metaspace 触发 Full GC 的阈值语义（committed 达 `-XX:MaxMetaspaceSize`）及弹性 Metaspace
  （16，JEP 387）带来的 uncommit 行为，见 jvm-1.md 1.1——它决定了"Metadata GC Threshold 频繁出现
  但堆没满"这类日志该查类加载还是查堆。
- 巨型对象与 Netty 池化的关系（为什么堆外分配天然绕开 G1 Humongous），见 jvm-1.md 四。

> 与 [`jvm-1.md`](jvm-1.md)（JVM 演进笔记）的分工：那边管 JVM 演进机理，本文是机理之上的
> **实战动线**——两篇合起来读：机理 → 排查。
