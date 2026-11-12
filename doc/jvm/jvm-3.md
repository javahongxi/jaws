# JVM 实战笔记：Linux 判断进程 CPU 高低 —— top · pidstat · /proc · 线程定位

> 定性：个人学习笔记，非框架文档。主题偏 Linux 侧进程 CPU 占用判断，作为 JVM 实战排查
> （定位高 CPU 线程 → jstack/JFR 找代码）的前置动线（截至 2026-11）。

## 0. 结论先行

判断一个进程 CPU 高不高，核心是**看它占用的 CPU 时间占比**——注意百分比的基准是"单核"，
多核机器上 400% 表示占满 4 核；瞬时高低靠 `top`/`pidstat` 采样，长期平均用 `ps`，
脚本化监控从 `/proc/<PID>/stat` 两次采样做差。

## 一、top / htop（最常用）

```bash
top -p <PID>          # 只监控指定进程
htop -p <PID>         # 交互界面，按 F5 看线程视图
```

进入 top 后按 `H` 切换线程视图，能看到具体哪个线程吃 CPU。

关键看 `%CPU` 列，注意：**默认是单核基准**，多核机器上 400% 表示占满 4 核。按 `1` 展开各核，
或用 `top -H -p <PID>` 后按 `Shift+i` 切换成"按 CPU 总数归一化"。

## 二、pidstat（最推荐，可精确统计）

来自 `sysstat` 包：

```bash
pidstat -p <PID> 1        # 每 1 秒采样一次
pidstat -t -p <PID> 1     # 细化到线程，定位热点线程
pidstat -u -p <PID> 5 3   # 采样 5 秒一次共 3 次
```

`%CPU` 列直接给出占比，`usr`/`system` 区分用户态和内核态：

- 用户态高 → 业务代码计算密集或 GC 频繁；
- 内核态高 → 系统调用/上下文切换频繁。

## 三、ps（看生命周期平均值）

```bash
ps -p <PID> -o pid,%cpu,etime,cmd
```

注意 `ps` 的 `%cpu` 是**进程启动以来的平均值**（cpu 时间 / 运行时长），瞬时高低看不出来，
只适合判断"长期平均"。

## 四、从 /proc 直接算（脚本监控用）

```bash
cat /proc/<PID>/stat        # 第 14、15 字段: utime, stime（单位: clock ticks）
cat /proc/<PID>/status | grep State
```

两次采样做差，除以时间间隔和 `getconf CLK_TCK`（通常 100），即可算出占用率，适合写监控脚本：

```bash
# 原理: (Δutime + Δstime) / CLK_TCK / Δwall_time / 核数
```

## 五、辅助判断

```bash
cat /proc/loadavg                        # 整机负载
vmstat 1                                 # r 列为运行队列长度
lscpu                                    # 先确认核数，避免误判百分比基准
strace -c -p <PID>                       # CPU 高且 system 占比高时, 看系统调用
```

常用判断标准：

| 观察值 | 含义 |
|---|---|
| 单进程 %CPU 持续 > 100%（即超 1 核） | CPU 高 |
| %usr 高 | 业务计算密集或 GC 频繁（Java 可用 `jstack`/JFR 定位） |
| %sys 高 | 系统调用、上下文切换、内存回收频繁 |
| %wait 高（在 top 整体行） | 不是 CPU 瓶颈而是磁盘 IO 瓶颈 |

## 六、最佳实践组合

`top -Hp <PID>` 找到高 CPU 线程 → 线程 ID 转 16 进制 → 配合 `jstack` /
`perf top -t <TID>` 定位到具体代码。Java 进程用 `top -Hp` + `jstack` 是经典套路。
