# Harbor Raft：持久实例与配置中心的 CP 设计精读

> harbor 的第二套一致性档位。ephemeral 实例继续走「连接即活性 + Distro AP」；`ephemeral=false` 的持久实例与**配置中心的全部写入**走**自研最小 Raft**——数据只存**内存 + 本地日志/快照文件**，不依赖任何外部数据库，与 Nacos 的 JRaft 部署形态完全同构。本文既是 Raft 算法在 harbor 里的落地精读，也是与 Nacos CP 链路（Nacos 3.2.2 源码为准绳）的逐段对照。引用只到类/概念/默认值粒度，不钉行号（同 `harbor-vs-nacos.md` 的维护纪律）。

## 0. 为什么是自研，以及范围

两个已拍板的决策：

1. **自研最小 Raft，不引入 SOFA JRaft**。目标是学习 Raft 本身：选举、日志复制、快照、持久化时序全部手写并可单步调试；JRaft 的 disruptor、复制流水线、成员变更、ReadIndex 等工程层在这里用不上，引入它反而把算法埋进框架。
2. **naming 持久实例 + config 同组**。config 的发布/删除与持久注册共用同一个 Raft group、同一条日志（单 group 双命令方言），不做多 group 拆分；不做 console、版本回滚、灰度发布、审计；不做服务元数据的独立 CP 存储（Nacos 的 `SERVICE_METADATA` group 那一路）。

于是 harbor 一个进程里同时跑两套一致性协议，按请求类型分流——这正是 Nacos 的形状（naming v2 的 ephemeral 走 Distro，persistent 走 JRaft），只是 Nacos 把它们拆在 `consistency` 模块里，harbor 把它们放在 `distro/` 与 `raft/` 两个包里。

## 1. 全景：一次持久注册的完整链路

```
HarborClient.registerInstance(ephemeral=false)
  → PersistentInstanceRequest（独立请求类型，非 InstanceRequest）
  → HarborServer.handlePersistentInstanceRequest
      校验 + 混用预检 → clientId = ip:port#persistent
      → NamingCommand.register(...)
  → HarborRaftProtocol.write
      本节点是 leader？ → RaftNode.propose
      不是？ → RaftWriteForwardRequest 转发给 leader（最多 3 次尝试，NotLeader 带 hint）
  → RaftNode：追加本地日志（fsync）→ AppendEntries 复制 → 多数派匹配 → commit → apply
  → HarborStateMachine.onApply → ServiceStorage.registerPersistentInstance
      （持久 ClientSession，与连接生命周期无关）
  → future 完成 → InstanceResponse(success)
```

读路径不变：持久实例落在同一套 `publisherIndexes`/`serviceDataIndexes` 里，任何节点的查询/订阅/推送都照常工作——**CP 只改变「写怎么被担保」，不改变「读长什么样」**。

两套协议**共享同一套内存模型**（ClientSession + ServiceStorage 索引），但权威来源是两套：临时实例的权威是「持有连接的那个节点」，由 Distro 把权威副本传播出去；持久实例的权威是 **Raft 日志本身**——复制的是命令而非状态，每个节点从同一条日志**独立重建**出同一份持久 session 与索引，节点之间不传数据本身。

config 写入走完全同构的一条链，只是命令方言与落点不同：

```
ConfigService.publishConfig / removeConfig（nacos-client、HarborClient 或 HTTP /api/config）
  → ConfigPublishRequest / ConfigRemoveRequest → HarborServer
  → ConfigCommand.publish(...) / remove(...)
  → HarborRaftProtocol.write(ConfigCommand)（与 naming 同一套 leader 转发）
  → Raft commit → HarborStateMachine.onApply → ConfigStorage put/remove
  → ConfigChangeNotifier → 本节点 ConfigListenContext 按 md5 推送给监听者
```

每个节点 apply 同一条日志后**各自推自己的本地监听**，节点间不中继推送——复制担保来自 Raft，推送只是本地通知。config 读与 naming 一样直查本地内存（同一档 stale read 语义）。

## 2. 概念映射：harbor ↔ Nacos/JRaft

| harbor | Nacos / JRaft 对应 | 说明 |
|---|---|---|
| `RaftNode` | JRaft `NodeImpl` + `Replicator` | 算法核心，单线程事件循环（JRaft 用 disruptor 达成同一单写者纪律） |
| `RaftLog` | JRaft `LogManager` 的内存视图 | Log Matching Property：冲突截断、幂等重发、快照边界 |
| `RaftStorage` | JRaft `RaftMetaStorage` + `LogStorage` + `SnapshotStorage` | 三个文件合成一处：`meta.json` / `log.jsonl` / `snapshot.json` |
| `RaftTransport` / `WireRaftTransport` | JRaft RPC 层（`RpcService`） | 异步接口；实现照 `WireHarborNodeTransport` 的模式走 wire 一元 |
| `StateMachine` / `HarborStateMachine` | JRaft `StateMachine` / Nacos `NacosStateMachine` | `onApply` 按 operation 分流 naming/config 两种命令方言；快照 = 持久 client 全量 + config 全量 |
| `NamingCommand` | Nacos `WriteRequest`（ADD/DELETE/CHANGE） | REGISTER / DEREGISTER / HEALTH / NOOP 四种操作 |
| `ConfigCommand` | Nacos config 的 `ConfigPublishRequest`/`ConfigRemoveRequest` 落库动作 | CONFIG_PUBLISH / CONFIG_REMOVE；casMd5 忽略，last-write-wins |
| `ConfigStorage` / `ConfigItem` | `ConfigCacheService` + `CacheItem`（去掉数据库层） | 内存 groupKey 索引，content/md5/lastModified |
| `ConfigListenContext` / `ConfigChangeNotifier` | `ConfigChangeListenContext` + apply 后的本地通知 | 监听按连接登记，md5 比对后只推变化键 |
| `HarborRaftProtocol` | Nacos `CPProtocol`（JRaft 门面） | 写入口 + leader 转发；Nacos 由 JRaft 的 redirect 机制代劳 |
| `PersistentInstanceRequest` | **同名**（nacos api 请求类） | 能力协商通过时 nacos-client 发的就是它 |
| `ClientSession.persistent(clientId)` | `IpPortBasedClient`（`isEphemeral()=false`） | 出生定死身份，与连接无关；clientId = `ip:port#persistent` |
| `ServiceStorage.registerPersistentInstance` 等 | `PersistentClientOperationServiceImpl` | 持久写操作只由 raft apply 线程驱动 |
| `PersistentHealthProbe` | `HealthCheckTaskV2` + `TcpHealthCheckProcessor` | 服务端主动 TCP 探活 |
| HEALTH 命令走 Raft | `PersistentHealthStatusSynchronizer` → `updateInstance` | healthy 标志本身是 CP 复制数据 |
| `HarborStateMachine.saveSnapshot` | `PersistentInstanceSnapshotOperation` | 快照落本地文件，重启快照+日志恢复 |
| `SetupAckRequest.abilityTable` 填 `supportPersistentInstanceByGrpc=true` | `ServerAbilities` + `AbilityKey.SERVER_PERSISTENT_INSTANCE_BY_GRPC` | 客户端据此选 gRPC 持久路径而非降级 HTTP v1 |

## 3. Raft 核心精读

### 3.1 线程模型：一切状态迁移都是事件

所有可变状态（term/vote/role/log/commitIndex/nextIndex…）**只活在一条 `harbor-raft` 线程上**。RPC 到达、transport 回包、timer tick、客户端 propose 全部 `post()` 成事件进队列；timer 线程只 post 不碰状态，transport 是纯异步（`CompletableFuture`）。算法本体因此**零锁**——这与 JRaft 用 disruptor 串行化 `NodeImpl` 事件是同一个判断：Raft 的状态机足够小，单写者比细粒度锁便宜得多。

对外读（`status()`/`isLeader()`/`leaderId()`）走一个 volatile 的不可变 `RaftStatus` 快照，每个事件处理后刷新——读侧永远不阻塞算法线程。

### 3.2 选举

- **随机超时**：默认 1000–2000ms（`RaftOptions.defaults()`），心跳 300ms——论文建议的「选举超时远大于心跳」比例，避免 follower 把忙碌的 leader 误判为死。timer 以 `min/2` 节拍检查 deadline。
- **投票限制（§5.4.1）**：candidate 的日志必须至少和自己一样新（先比 lastLogTerm，再比 lastLogIndex），否则拒票。这条保证选出的 leader 一定持有全部已提交条目。
- **先持久化再应答（§5.2）**：授票前 `saveMeta(currentTerm, votedFor)` 原子写 + fsync；崩溃重启不会忘记本 term 已投过谁，杜绝双 leader。
- **单节点 = 单成员组**：`start()` 时 peers 为空 → 立即发起选举、立即当选。standalone harbor 不需要任何特殊分支就拥有完整 CP 语义（同样写日志/快照，重启可恢复）。
- 任期更高的回包一律 `stepDown`；stepDown 时所有 pending write 以 `NotLeaderException` 失败——**未提交的写宁可失败也不悬挂**，调用方（`HarborRaftProtocol`）据此换 leader 重试。

### 3.3 日志复制与 commit

- **prevLog 检查 + 冲突截断（§5.3）**：follower 在 `RaftLog.appendFollower` 里逐条比对——同 index 同 term 的条目幂等跳过，term 冲突则截断该位置及其后全部，再取 leader 版本。截断预测（`conflicts()`）驱动日志文件重写。
- **快速 nextIndex 回退**：AppendEntries 被拒时不再一格一格退，直接跳到 `min(next-1, follower.lastLogIndex+1)`，**下限钳到 1**。钳制位置是刻意的：若 follower 落后到 leader 快照边界之下，`next <= lastIncludedIndex` 会自然落入 InstallSnapshot 分支——钳到 `lastIncludedIndex+1` 反而会让 prevLog 检查永远失败、死循环。
- **commit 规则（§5.4.2，Figure 8）**：只有**当前 term** 的条目能靠数副本推进 commitIndex；旧 term 条目搭车提交。新 leader 当选即追加一条 **NOOP**，保证「当前 term 条目」必然存在——这也是重启恢复能重新担保旧日志的机制（见 §3.5）。
- 批量上限 `MAX_ENTRIES_PER_APPEND=128`；成功后若仍有积压立即续发，不等下一拍心跳。
- 批量心跳即空 AppendEntries，300ms 一拍。

### 3.4 快照与 InstallSnapshot

- **触发**：apply 计数达到 `snapshotThreshold`（默认 10000）即压实。
- **取点**：快照永远取在 `lastApplied` 这个稳定点——`saveSnapshot()` 与随后读到的日志边界严格一致，InstallSnapshot 直接复用同一函数，不存在「镜像与边界错位」的窗口。
- **压实顺序**：状态机镜像原子落盘 → `RaftLog.truncatePrefix` → 日志文件整体重写。
- **落后 follower**：leader 发现 `nextIndex <= lastIncludedIndex` 时发 InstallSnapshot（整包 Base64，不分块——见 §7 偏离清单）；follower `reset` 日志到快照边界、`loadSnapshot` 重建状态机，之后正常追日志。

### 3.5 持久化与恢复时序

三个文件，一个目录（`dataDir/raft-<port>/`）：

| 文件 | 内容 | 写时机 |
|---|---|---|
| `meta.json` | currentTerm + votedFor | 任何投票/任期变化**应答之前**，原子替换 + fsync |
| `log.jsonl` | 每行一条 LogEntry（JSON + Base64 data） | AppendEntries 成功**回包之前** fsync；leader propose 同样先落盘 |
| `snapshot.json` | 状态机镜像 + lastIncludedIndex/Term | 压实/InstallSnapshot 时原子替换 |

**恢复顺序**：读 meta → 读快照（状态机 `loadSnapshot`，commitIndex=lastApplied=快照边界）→ 读快照边界之后的日志后缀进 `RaftLog`。**关键语义：日志后缀只进结构、不进状态机**——那些条目可能从未提交，重启后必须等新 leader（单节点即自己）的当前 term NOOP 提交时再一并 apply。这正是 Figure 8 规则在恢复路径上的体现：崩溃前没拿到多数派担保的写，重启后不会擅自生效。

**撕裂尾行容忍**：崩溃在 append 半途会留下半行 JSON——它从未被应答过，恢复时直接丢弃（`RaftStorage.loadLog`），不是致命错误。

**压实崩溃窗口**：压实是「先写 snapshot.json、后重写 log.jsonl」两步（§3.4），若恰好崩在中间，日志文件里还留着已被快照覆盖的前缀——恢复时 `loadLog(afterIndex)` 只收 `index > 快照边界` 的条目，被覆盖的前缀按索引直接跳过，不会二次 apply。快照边界坐标（lastIncludedIndex/Term）与镜像同文件原子落盘，就是为这个窗口准备的保险。

## 4. 状态机与存储集成

- **`NamingCommand`**：JSON 命令，四种操作——REGISTER / DEREGISTER / HEALTH / NOOP。对应 Nacos `WriteRequest` 的 ADD/DELETE/CHANGE；NOOP 是 Raft 自己的（JRaft 也有等价物）。
- **`HarborStateMachine.onApply`**：按 operation 前缀分流——`CONFIG_*` 走 `ConfigCommand`，其余走 `NamingCommand` 分发到 `ServiceStorage` 的持久操作区。这些方法**只允许 raft apply 线程调用**——与 AP 侧「写只发生在 owner」对称，CP 侧「写只发生在 apply」。
- **持久 session**：`ClientSession.persistent(clientId)` 出生即 `native=false, persistent=true`。它没有 `ConnectionRecord`，所以连接看门狗、`reconcileHealth`、`isReplicaOrphaned` 全都结构性地看不见它——持久实例**活过发送它的那条连接**，这正是定义本身。注销到空时 session 一并移除（持久 client 没有「保留空壳等心跳」的理由）。
- **混用守卫（双向）**：与 Nacos `EphemeralClientOperationServiceImpl`/`PersistentClientOperationServiceImpl` 的互斥同构。ephemeral 注册入口顶部 `refuseIfPersistentPublishers` 抛错；持久注册在 handler 预检 + apply 时确定性判定（apply 必须确定性——同一条日志在每个节点回放结果必须一致，所以守卫不能只靠 handler 侧的瞬时检查）。
- **Distro 排除（全部出口）**：`doSyncChange` 与 `onSnapshot` 显式跳过持久 session；verify 与补偿重推只遍历 native session（`allNativeClientSessions` / `resyncToPeer` 的 native 守卫），持久 session 由 apply 而生、不是 native，结构性地不在其中——与 Nacos `DistroClientDataProcessor` 构造快照/对账时 `!client.isEphemeral()` 直接跳过逐字同构。持久数据的复制担保只来自 Raft，AP 通道不碰它，否则两套协议会互相覆盖。
- **快照内容**：`SnapshotImage(naming, configs)` 一个镜像覆盖两套 CP 状态——naming 部分 `allPersistentSessions()` → 逐 session `buildClientSyncData`（复用 AP 侧 client 级载荷），config 部分 `ConfigStorage.allConfigs()` 全量 `ConfigItem`。`loadSnapshot` 先清后建，幂等；旧的 naming-only 裸 list 格式仍可读（configs 视为空）。

### 4.1 config 面：存储、推送与协议语义

- **`ConfigStorage`**：纯内存 groupKey（dataId+group+tenant）索引。入口归一化：空 tenant → `public`、空 group → `DEFAULT_GROUP`——与 Nacos 的默认命名空间/分组约定一致。
- **推送**：apply 后 `ConfigChangeNotifier.requestNotify` 驱动**本节点**的 `ConfigListenContext`——逐监听者比对持有的 md5 与当前 md5，只给变化者推 `ConfigChangeNotifyRequest`（只带键，不带内容），客户端收到后自行 query 拉新值。删除同样触发推送：当前 md5 视为空，与任何非空持有值都不一致。
- **namespace-transfer**：nacos-client 以空 tenant 登记监听，服务端存 `public`，但推送必须回显监听者登记时的 tenant（null）——客户端缓存按它发出的 tenant 建键，回显 `public` 会让缓存查找 miss，变更永远浮不出来。`ConfigListenContext` 逐连接记录 transferred 标记。
- **查询语义**：缺失键回 `CONFIG_NOT_FOUND`（300），且 **errorCode 与 resultCode 都填 300**——nacos-client 从 `errorCode` 读类型化失败，读作「配置没了」并给监听器 null 内容；缺了 errorCode 会被当通用错误，`getConfig` 转而回退本地快照，删除永远「看不见」。
- **连接清理**：`ConnectionCleanup` 摘除死连接的全部 config 监听（node-local，无 peer 同步——监听本来就是连接本地状态）。客户端 redo 表在重连后以持有 md5 重放 listen：服务端报 changedConfigs 才 query+回调，md5 未变则静默——重连不重复通知。
- **HTTP 写入面**：`POST /api/config` 直写单 key（dataId=key，group `JAWS_CONFIG`，空 tenant 归一化 `public`）走同一条 `publishConfig` Raft 链；`GET /api/configs` 列出本节点内存全量。详见 `dynamic-config.md`。

## 5. 写转发、客户端与能力协商

- **`HarborRaftProtocol.write`**：`write(NamingCommand)` 与 `write(ConfigCommand)` 两个重载共用同一条转发链，最多 3 次尝试。本节点是 leader 直接 propose（5s 等 commit+apply）；`NotLeaderException` 带 leaderId hint 则换目标重试；否则用 `raftNode.leaderId()` 猜一个，经 `RaftWriteForwardRequest`  relay。**被转发方（`onForwardWrite`）是 leader 就直接 propose、不再转发**——防环。没有可用 leader 时响亮失败（"no raft leader available; retry later"），不做无限等待。
- **`HarborClient`**：`sendInstanceRequest` 按 `instance.isEphemeral()` 分叉——ephemeral 发 `InstanceRequest`，persistent 发 `PersistentInstanceRequest`，与 Nacos `NamingClientProxyDelegate` 的分流同形。redo 重放经过同一分叉，所以断线重连后持久注册以**幂等替换**的方式重放，不会重复。batch 仍仅限 ephemeral（持久实例不支持 batch，响亮拒绝）。
- **能力协商**：`SetupAckRequest.abilityTable` 填 `supportPersistentInstanceByGrpc=true`。真 nacos-client 见到该能力即走 gRPC `PersistentInstanceRequest`，不再降级 HTTP v1——这是 Nacos 3.x 客户端选择持久路径的唯一开关。
- **边界仍硬**：`InstanceRequest` 携带 `ephemeral=false` 依旧被拒（那会静默把持久实例降级成连接所有物）；拒绝文案指向 `PersistentInstanceRequest`。

## 6. 健康探测：leader-only TCP

`PersistentHealthProbe` 是 Nacos `HealthCheckTaskV2 + TcpHealthCheckProcessor` 的对应物，但有一处刻意收紧：**只有 leader 探测**。

- 3s 一拍，对每个持久实例 TCP connect（1s 超时），4 worker、整轮 3s 预算（超预算按不可达计）。
- 判定翻转不直接改内存——封装成 **HEALTH 命令走 Raft**，每个节点在**同一日志位置**翻转 healthy 标志（Nacos `PersistentHealthStatusSynchronizer` 的形状）。healthy 是 CP 数据，不是各节点各自的观测。
- leader 易主无需交接状态：新 leader 下一拍自然接管探测。

## 7. 刻意偏离清单（对 Nacos/JRaft）

| 偏离 | Nacos/JRaft 怎么做 | harbor 怎么做 | 代价与理由 |
|---|---|---|---|
| **leader-only 探测** | 每个节点按一致性哈希分担探测任务，翻转经 CP 同步 | 只有 leader 探测 | 省掉任务分片与再平衡；探测量 = 持久实例数，注册中心规模下 leader 单点探测不构成瓶颈。实例数上万时再分片 |
| **stale read** | JRaft 提供 ReadIndex/lease read 路线（Nacos naming 读实际也走内存索引） | 读直接查本地内存索引，可能落后 leader 一个复制窗口 | 与 Nacos naming 的实际读语义一致；线性一致读要 ReadIndex 心跳确认，收益对服务发现场景不成比例 |
| **静态成员** | JRaft 支持 joint consensus 成员变更 | peers 在 `start()` 从 `ClusterManager` 捕获，终身不变 | 与 harbor AP 侧静态成员一致；改成员 = 改配置重启。论文 §6 成员变更是最易错的部分，最小实现不碰 |
| **clientId 后缀 `#persistent`** | `ip:port#false` | `ip:port#persistent` | 语义自明；两侧 clientId 本就不互通（各自内部键），无兼容代价。**读 Nacos 源码时注意这个差异** |
| **单文件 JSON 存储** | JRaft 分段日志文件 + 独立快照目录 + RocksDB 可选 | 三个文件、JSON 行、Base64 | 可读性优先（学习案例可以直接 `cat log.jsonl`）；万级条目后重写成本上升，届时分段是自然演进 |
| **InstallSnapshot 整包** | JRaft 分块流式传输 | 单包 Base64 一元 | 与 harbor「节点间全一元」的既有边界一致；快照 = 持久 client 全量 + config 全量，规模大时再分块 |
| **不做 PreVote** | JRaft 默认开启 | 无 | 分区恢复的旧 leader 靠 term 规则自然 stepDown（测试钉住）；PreVote 防的是「分区节点回来扰乱任期」，最小实现接受这个扰动 |
| **探测仅 TCP** | tcp/http/mysql 三种 processor | 仅 TCP connect | http/mysql 探测是产品面；机制层 TCP 已覆盖「地址可达性」这一核心语义 |

## 8. 配置与可观测性

- **数据目录**：URL 参数 `dataDir`（默认 `~/.harbor/data`，固定在用户目录、不随进程工作目录漂移），每节点独占 `raft-<port>` 子目录；`HarborBootstrap --data-dir <dir>`（或 `-Dharbor.dataDir`）可覆写，启动横幅会打印实际目录。同机多节点共父目录不冲突。
- **时序覆写**（测试用）：`raftElectionTimeoutMinMs` / `raftElectionTimeoutMaxMs` / `raftHeartbeatMs` / `raftSnapshotThreshold`。
- **`GET /api/raft`**（管理端口 = grpcPort+10）：nodeId / role / term / leaderId / commitIndex / lastApplied / lastLogIndex / persistentClients。
- **`POST /api/config` / `GET /api/configs`**（同一管理端口）：前者把单个 jaws key 直写成独立 dataId（group `JAWS_CONFIG`）并走 Raft；后者列本节点内存 config 全量（dataId/group/tenant/md5/lastModified/content）。契约见 `dynamic-config.md`。
- 演示：`jaws-sample-raft-provider` 的 `RaftProvider`——一个 persistent DemoService 实例（group `persistent`），消费端用 `jaws-sample-raft-consumer` 的 `RaftConsumer`；类注释给出 kill -9 provider / kill -9 harbor 的完整验证步骤。

## 9. 测试锚定

| 测试 | 钉住的语义 |
|---|---|
| `raft/RaftLogTest` | Log Matching：冲突截断、幂等重发、快照边界代答 prevLog、reset、批量上限 |
| `raft/RaftStorageTest` | term/vote 重开存活、日志有序持久、撕裂尾行丢弃、压实前缀跳过、重写后 append 句柄复用、快照往返 |
| `raft/RaftNodeTest` | 内存 transport 上的算法本体：单节点立即自选、三节点单 leader 全复制、follower propose 带 leader hint、leader 死亡重选、**分区 leader 不能独自 commit 且愈合后 stepDown + 悬挂写失败**、落后 follower 经 InstallSnapshot 追上 |
| `HarborPersistentInstanceTest` | 单节点端到端：注册/注销走 CP 日志、混用双向拒绝且不落状态、**同 dataDir 重启恢复**、探测打死端口翻 unhealthy / 复活翻回 healthy |
| `HarborPersistentClusterTest` | 3 节点真 wire：follower 收写 → 转发 → 全节点可见；leader 宕机 → 重选 → 已提交数据仍在且接受新写 |
| `CapabilityBoundaryTest` | 边界迁移：`PersistentInstanceRequest` 走 CP 成功、持久注册活过连接；`InstanceRequest` 带 `ephemeral=false` 仍先拒后写；config fuzzy watch 仍是边界 |
| `ConfigCenterTest` | 真 nacos-client `ConfigService` 单节点端到端：发布/查询/删除、监听推送与删除-as-null（钉 namespace-transfer 与 errorCode=300 语义）、**同 port+dataDir 重启后已提交配置仍在** |
| `ConfigRaftClusterTest` | 3 节点真 wire：follower 收 HTTP 写 → Raft 复制 → 全节点 ConfigStorage 按 key 收敛；监听者挂非写入节点，per-key 变更经 apply 后本地推送到 nacos-client，写一个 key 不碰另一个 |
| `client/HarborClientConfigTest` | 原生 SDK config 面：晚订阅拿当前值、删除-as-null、unlisten 后 redo 收集、**重连回放 listen 后变更仍到达**、无变更重连不重复回调 |
| `HarborDynamicConfigurationTest`（jaws-registry-harbor） | config SPI 对齐 nacos 腿：按 protocol 加载、config 独立连接（与 naming 各一条）、per-key 发布/远端变更/删除-as-null/unlisten 静默/晚读远端已提交值 |

## 10. ZAB 对照：同代协议的另一种形状

ZAB（ZooKeeper Atomic Broadcast）是 ZooKeeper 自研的共识协议，随 ZooKeeper 以 Apache 2.0 开源（实现在 `org.apache.zookeeper.server.quorum`，核心类 `Leader`/`Follower`/`FastLeaderElection`）。它与 Raft 同属强 leader + 过半提交 + 任期号的 primary-backup 家族，Raft 论文把 Zab 列为同谱系的相关工作。区别在视角：**Raft 以「日志复制」为中心设计，ZAB 以「原子广播 + 崩溃恢复」为中心设计**——后者服务于 ZooKeeper 的 watch/通知语义，保证状态变更的全局有序投递。

ZAB 把运行分为两种模式。**崩溃恢复**：FastLeaderElection 按 `(epoch, zxid, myid)` 投票，优先选数据最新者；选出 leader 后进入**显式同步阶段**——leader 与 follower 双向对账，按差异程度补发事务（DIFF）、截断多余事务（TRUNC）或全量快照（SNAP），同步完成前不对外服务。**消息广播**：写全部转发 leader，每个写生成 **zxid**（64 位：高 32 位 epoch 任期号，低 32 位单调计数器），以类 2PC 的方式 PROPOSAL → 过半 ACK → COMMIT；与标准 2PC 不同，follower 不能否决提案、过半即可提交，不会被单节点阻塞。

| 维度 | ZAB | Raft（harbor 实现） |
|---|---|---|
| 设计目标 | 原子广播：状态变更的全局有序投递，服务 watch 语义 | 复制状态机：日志即真相，强调可理解性与通用性 |
| 任期与编号 | zxid 一个数编码 epoch + 计数器，epoch 在选举投票时递增 | term 与 log index 独立（`meta.json` 的 currentTerm + 日志索引） |
| 选举限制 | 选 zxid 最大者，新 leader 数据天然最全 | 先比 lastLogTerm 再比 lastLogIndex（§3.2），保证等价 |
| 选举后对齐 | 独立同步阶段，双向对账（DIFF 补 / TRUNC 截 / SNAP 全量），完成前不服务 | 无独立阶段：prevLog 检查 + 冲突截断（§3.3）在每次 AppendEntries 里隐式对齐；新 leader 提交 NOOP 确认 commitIndex |
| 未提交数据处理 | 恢复阶段集中 TRUNC 回滚 | 分布式截断：follower 在 term 冲突处截断取 leader 版本；重启后缀只进结构不进状态机（§3.5） |
| 读语义 | follower 本地读可能旧，需 `sync()` 才线性一致 | 经典 Raft 读走 leader（或 ReadIndex）；harbor 刻意 stale read（§7），反而更接近 ZK 的常态读 |
| 成员变更 | 早期需重启集群，3.5+ 支持动态 reconfig | 论文原生 joint consensus；harbor 静态成员（§7） |

正确性上两者等价（线性一致写、顺序投递）；工程上 Raft 更模块化、易实现，后来者几乎一律选 Raft——etcd、TiKV、CockroachDB、JRaft（Nacos CP 用它）、Kafka KRaft 皆是。ZAB 则基本是 ZooKeeper 的专属协议，没有像 Raft 那样抽成通用库，存量活在 ZK 生态里：HBase、Hadoop HA、Dubbo 的 ZK 注册中心（本仓库 `jaws-registry-zookeeper` 走 Curator 即此路）都间接依赖它；Kafka 弃 ZK 转 KRaft 是这一趋势最标志性的事件。

对 harbor 的映照：ZAB 用一个独立同步阶段完成的事，Raft 拆成两处日常机制——冲突截断分摊进每次 AppendEntries，「已提交不丢、未提交不生效」由选举限制 + NOOP + 恢复时序（§3.5）联合担保。harbor 的 `RaftLog.appendFollower` 截断与 ZAB 的 TRUNC 同构，差别只在触发时机：ZAB 集中在恢复期，Raft 分散在复制期。

## 11. Paxos 对照：同一家族的祖先形状

Paxos 是 Lamport 1998 年《The Part-Time Parliament》提出的共识协议（2001 年《Paxos Made Simple》重述），解决的是比日志复制更原始的问题：**一群可能宕机、网络可能丢包乱序的节点，如何对单个值达成不可推翻的共识**——值一旦被选定（chosen）就永不更改。它没有单一权威开源实现，正确性证明与工程变体散在各家系统里（Google Chubby/Spanner 的 Multi-Paxos 变体、微信 PhxPaxos、OceanBase 早期版本）。Raft 论文自认是 Paxos 家族的后代：Ongaro 的直接动机正是「Paxos 正确但太难理解、太难实现对，所以设计一个可理解的等价物」。与 ZAB 是「同代兄弟」不同，Paxos 是这一谱系的**祖先**——Raft 可以看作 Multi-Paxos 强 leader 路线的工程化重写。

Basic Paxos 分两阶段，角色为 Proposer/Acceptor/Learner，提案带全局递增编号 n。**Prepare（抢占提案权）**：Proposer 向多数派发 `Prepare(n)`；Acceptor 若 n 大于见过的所有编号，就承诺不再接受编号更小的提案，并回报自己已接受过的最大编号提案。**Accept（提交值）**：拿到多数派承诺后发 `Accept(n, v)`——v 不能随便选，回报里若有人接受过值，必须继承其中编号最大者，都没接受过才能用自己的值；多数派接受即选定。安全性靠两条交织：任何两个多数派必有交集，「先承诺后接受」+「必须继承已见过的最大值」保证两个不同的值不可能都凑齐多数派。但 Basic Paxos 只保证安全不保证终止——两个 Proposer 互相用更大的 n 抢来抢去会活锁；要连续定多个值，得靠 **Multi-Paxos**：选一个稳定 leader 当唯一 Proposer，跳过阶段一直接连续跑阶段二。走到这一步，形状已经非常接近 Raft。

| 维度 | Paxos / Multi-Paxos | Raft（harbor 实现） |
|---|---|---|
| 共识对象 | 单个任意值；Multi-Paxos 逐个定值拼成序列 | 日志条目：值被约束为「某 index 某 term 的命令」，复制状态机 |
| leader 地位 | 可选优化——无 leader 也能定值，只是易活锁 | 协议前提：无 leader 不能推进任何写（§3.2 选举是唯一入口） |
| 日志形状 | Multi-Paxos 允许乱序定值、留空洞后补 | 禁止空洞：prevLog 检查保证连续（§3.3），空洞在结构上不可能出现 |
| 安全性机制 | 提案编号 + 承诺 + 继承最大值，逐值论证 | 选举限制（§3.2）+ 当前 term 提交规则（§3.3 Figure 8），一次论证覆盖整条日志 |
| 未提交数据处理 | 已接受未选定的值可被更大编号提案覆盖，无「截断」概念 | 分布式截断：term 冲突处截断取 leader 版本；重启后缀只进结构不进状态机（§3.5） |
| 终止性 | Basic Paxos 有活锁；Multi-Paxos 靠稳定 leader 规避 | 随机选举超时 + 单 leader 天然规避活锁（§3.2） |
| 成员变更 | 原始论文未覆盖，各变体自行扩展 | 论文原生 joint consensus；harbor 静态成员（§7） |

正确性上两者等价（都是多数派交集担保的共识）；差别在**约束的多少**：Raft 用「强 leader + 日志连续 + 选举限制」三条额外约束，把 Paxos 里逐值论证的安全性收敛成一次性论证，代价是失去无 leader 定值与乱序定值的自由——对复制状态机场景这些自由本来就用不上。这正是 Raft 论文标题「In Search of an Understandable Consensus Algorithm」的含义：不是更强的协议，而是更好懂的协议。工程后果也由此而来：Paxos 的正确性极难证、实现极易错，从未像 Raft 那样抽成通用库（etcd/TiKV/JRaft/Kafka KRaft 一律选 Raft，Nacos CP 用 JRaft 即此路）；直接用 Paxos 系的系统（Chubby/Spanner、PhxPaxos）都是大厂以专门团队维护的私有变体。

对 harbor 的映照：Paxos「继承已见过的最大值」与 harbor 的选举限制（§3.2 先比 lastLogTerm 再比 lastLogIndex）是同一个思想——新权威必须持有全部已担保的过去；差别在 Paxos 逐提案编号论证，Raft 把它特化成「日志最新者才能当选」一条规则。harbor 的 NOOP（§3.3）在 Paxos 里没有对应物——它是 Raft 为「旧 term 条目搭车提交」专门补的机制，也是 Figure 8 反例的解药；裸 Multi-Paxos 不需要它，因为它压根没有「按 term 数副本」的提交规则。可以说 harbor 里每一处「刻意多出来的机制」，都是 Raft 用约束换可理解性时付出的找零。

## 12. ReadIndex 与线性一致读：刻意不担保的读

**线性一致（linearizable）**要求每个操作看起来在调用与返回之间的某个瞬间原子生效，且尊重真实时间顺序。落到读写上最直白的一条：**写一旦收到成功应答，之后任何节点发起的读都必须能看到它**（或更新的值）。harbor 的读不满足这条——§7 的 stale read：查询直查本节点内存索引，可能落后 leader 一个复制窗口。典型场景：t1 向 leader 注册实例 X 拿到成功应答，t2 立刻向 follower B 查询，B 还没 apply 到那条日志，查不到 X——写已担保、读却「看不见刚写成功的值」。config 面更刺眼：发布成功后另一节点 getConfig 可能拿到旧值。这不是遗漏，是刻意选择；本节把「若要担保，代价是什么」讲清。

为什么不能「读 leader 内存」就完事？两个坑都与既有机制相关：其一，**leader 可能已被废黜而不自知**——网络分区后旧 leader 还在收读请求，新 leader 已在另一侧提交新写，旧 leader 的内存是过期的（与 §3.2 stepDown 规则同源：term 更高的回包才触发退位，分区期间收不到）；其二，**新 leader 的内存也不全**——当选只保证日志最全，不保证状态机最全，commitIndex 要等当前 term 的 NOOP 提交才推进（§3.3），当选瞬间 lastApplied 可能还停在旧位置（§3.5 恢复时序同理）。

| 路线 | 原理 | 每次读代价 | 担保与前提 |
|---|---|---|---|
| 读走日志（Read as Log） | 把读本身当命令 propose，等 commit + apply 后读状态机 | 一轮完整复制（写日志 + fsync + 多数派） | 绝对正确，但读付写的代价，无人采用 |
| **ReadIndex**（论文 §6.4） | leader 记当前 commitIndex 为 readIndex → 一轮心跳确认仍被多数派承认 → 等 `lastApplied >= readIndex` 后直读本地状态机 | 一轮心跳 RTT + 等 apply，不占日志 | 线性一致；心跳确认堵废黜坑，等 apply 堵新 leader 坑 |
| Lease read | 连心跳都省：leader 靠时钟租约断言「选举超时内我必然还是 leader」，直接读 | 近乎零 | 线性一致，但前提是各节点时钟漂移有界——用硬件假设换性能 |
| **harbor stale read（§7）** | 任何节点直查本地内存索引 | 零 | 不担保：可能落后一个复制窗口；与 Nacos naming 实际读语义一致 |

采用面上，线性一致读是 KV 存储的标配而非注册中心的：etcd 默认 ReadIndex，TiKV 提供 lease read 与 follower read（follower 向 leader 要 readIndex 后本地等 apply，读流量不打 leader）；JRaft 同样提供 ReadIndex/lease 路线，但 Nacos naming 的读实际直查内存索引（§7 已钉）——与 harbor 同侧。ZK 的常态读也是 follower 本地旧读，要线性一致得显式 `sync()`（§10）：三家注册中心在「读默认不担保」上站同一侧，只有把读当产品的系统才把 ReadIndex 当标配。

对 harbor 的映照：若将来要做，ReadIndex 是最自然的路线——算法状态全活在单线程事件循环上（§3.1），记 readIndex、等 apply 都是同一线程上的普通事件，零额外锁；心跳确认需按需发起一轮（不能复用 300ms 周期心跳，否则读延迟被心跳节拍绑架）；follower 读则需转发 leader 要 readIndex，形状与写转发（§5）同构。不做的理由仍是 §7 那条：服务发现晚一个复制窗口（百毫秒级）浮现实例完全可接受，而 ReadIndex 要给**每次读**加一轮心跳确认，收益对注册中心不成比例。边界清单里的「ReadIndex/线性一致读」与 stale read 是同一枚硬币的两面：读不担保是决策，不是欠账。

## 13. 边界（本期不做）

- Raft 成员变更（joint consensus）、ReadIndex/线性一致读、PreVote、日志分块 InstallSnapshot。
- 持久实例不支持 batch；config 不做 console、版本回滚、灰度发布、审计（fuzzy watch 仍是边界）；不做服务元数据独立 CP 存储。
- 持久实例的订阅推送复用 AP 侧 `PushDelayTaskEngine`——推送本身不需要 CP 担保（到点重读当前全量、幂等收敛），CP 只担保数据。