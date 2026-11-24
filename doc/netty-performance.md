# Netty 高性能优化分析

基于 Netty 4.1 / 4.2 源码（本项目依赖 4.2.20.Final），梳理其在高性能方面的主要设计和工作。

## 一、线程模型优化

### 1. Reactor 主从多线程模型

- **三种 Reactor 形态的演进**：单 Reactor 单线程（Redis 6.0 前的模式，accept 与读写挤一个线程）→ 单 Reactor 多线程（I/O 多线程但 accept 仍是单点）→ **主从 Reactor 多线程**（accept 与读写彻底分离），Netty 采用最后一种。
- **Boss-Worker 双组 EventLoopGroup**：`ServerBootstrap.group(bossGroup, workerGroup)`。Boss 组的 `NioServerSocketChannel` 只注册 `OP_ACCEPT`，接受连接后把子 Channel 注册到 Worker 组中某个 EventLoop 的 Selector 上，此后该连接的所有读写都由这个 EventLoop 负责。
- **Channel 与 EventLoop 终身绑定**：一个 Channel 整个生命周期只属于一个 EventLoop，一个 EventLoop 可服务多个 Channel。这带来两个关键收益：
  - Channel 内部状态（pipeline、outboundBuffer、注册状态）天然**线程封闭**，绝大多数路径无需加锁；
  - 同一连接上的事件天然串行有序，不存在并发读写交错。
- **EventLoop 的本质** = 一个永不退出的线程 + 一个 Selector + 若干任务队列，`run()` 循环三段式：`select()` 等待 I/O → `processSelectedKeys()` 处理就绪事件 → `runAllTasks()` 消化队列任务。
- **ioRatio 时间预算**：默认 50，即"处理 I/O 花了多久，就最多花同样长的时间跑任务队列"，防止任务积压饿死 I/O，也防止 I/O 风暴饿死任务；设为 100 则表示 I/O 处理完后把任务全部跑完。
- **默认线程数**：`2 × CPU 核数`（可用 `io.netty.eventLoopThreads` 覆盖），I/O 密集型场景下让核尽量跑满。
- **4.2 的演进**：`NioEventLoopGroup` 被 `MultiThreadIoEventLoopGroup` + `IoHandlerFactory`（如 `NioIoHandler.newFactory()`）取代，把"线程调度"与"I/O 多路复用实现"解耦——同一套线程模型可以挂 NIO、epoll、kqueue、io_uring 等不同 I/O 后端。

### 2. FastThreadLocalThread

- **JDK ThreadLocal 的痛点**：`ThreadLocalMap` 是开放寻址哈希表，get/set 要算哈希、冲突时线性探测，条目越多越慢；Entry 的弱引用 key 被回收后还需启发式清理，行为不可控。
- **Netty 的思路：空间换时间，索引直访**。每个 `FastThreadLocal` 实例在创建时分配一个**全局唯一的固定 index**（AtomicInteger 递增），线程侧的 `InternalThreadLocalMap` 底层是 `Object[]`，访问就是 `array[index]`——**O(1)、无哈希、无冲突**。
- **快慢两条路径**：`FastThreadLocalThread` 直接持有 map 字段走数组快路径；普通 JDK 线程访问 FastThreadLocal 时回退到 JDK ThreadLocal 慢路径兜底，保证正确性。
- **内置对象缓存**：`InternalThreadLocalMap` 尾部保留固定槽位缓存 `StringBuilder`、`CharsetEncoder/Decoder`、`ArrayDeque`、Recycler 的 Stack 等高频对象，编解码路径上反复取用，**几乎零分配**。
- **统一清理**：`FastThreadLocal.removeAll()` 一次性清空本线程所有槽位并归还缓存对象，配合池化线程复用防止泄漏；JDK ThreadLocal 只能逐个 remove。
- **代价**：数组槽位只增不减，且 index 全局分配——适合"线程数有限、每线程变量多"的服务器场景，不适合海量短命线程。

### 3. 位运算轮询选择 EventLoop

- 新 Channel 注册时由 `EventExecutorChooser` 轮询挑选 EventLoop，Netty 提供两种实现：
  - `PowerOfTwoEventExecutorChooser`：`executors[idx.getAndIncrement() & executors.length - 1]`，**位与替代取模**（除法指令慢一个数量级）；
  - `GenericEventExecutorChooser`：`Math.abs(idx.getAndIncrement() % executors.length)`，线程数非 2 的幂时回退用这个。
- 构造 chooser 时按 `isPowerOfTwo(线程数)` 一次性选定策略，热路径上没有任何分支判断。
- 细节：`idx` 持续递增直至 int 溢出为负，但**负数 & (2^n - 1) 的结果依然落在合法区间**，位运算版天然免疫溢出；取模版才需要 `Math.abs`。
- 注意轮询均衡的是**注册时刻的连接分配次数**，不是实时负载——Channel 绑定后不再迁移，长连接场景下各 EventLoop 的实际压力可能不均，这也是后来 adaptive 类负载均衡要在应用层做的原因。

## 二、I/O 多路复用优化

### 4. SelectedKeys 数组优化

- **JDK 的实现**：`SelectorImpl` 内部 `selectedKeys` / `publicSelectedKeys` 是 `HashSet<SelectionKey>`。每次 select 后遍历都要创建 Iterator，哈希表节点内存离散、指针跳跃，对 CPU 缓存极不友好；高并发下每秒数万次 select，Iterator 与节点操作是可观的 GC 与缓存开销。
- **Netty 的替换**：自定义 `SelectedSelectionKeySet`，底层是 `SelectionKey[]` 数组：
  - `add()` 就是尾插 + 必要时倍增扩容，O(1) 且零额外对象；
  - 维护 keysA/keysB 两个数组，`flip()` 交换复用——本轮消费的数组下一轮拿来写入，**稳态零分配**；
  - 遍历是连续内存顺序扫描，缓存行命中率高。
- **注入方式**：`openSelector()` 里通过反射（privileged 块）把 JDK Selector 的两个 Set 字段直接替换成自定义实现；JDK 模块化限制或 `-Dio.netty.noKeySetOptimization=true` 时优雅回退为原生 HashSet，功能不受影响。
- **消费侧配套**：`processSelectedKeysOptimized()` 直接 for 循环扫数组，处理完把槽位置 null 帮助 GC，不再走 Iterator。
- 这是一个典型的"**不改 JDK 源码、用反射把关键数据结构换成更优实现**"的工程手法，收益随 QPS 线性放大。

### 5. JDK epoll 100% CPU Bug 三层防御

#### Bug 表现

- 即著名的 **epoll 空轮询 bug**（JDK-6670302 等一系列 NIO Selector 缺陷），主要出现在 Linux 上的旧版 JDK 6/7。
- 典型触发场景：对端异常断开连接（如收到 RST），Socket 上产生 `EPOLLHUP`/`EPOLLERR` 事件。JDK 的 `EPollSelectorImpl` 在把内核事件翻译成 `selectedKeys` 时处理有缺陷：**事件没被正确消费，也没有从 epoll 关注集中消除**。
- 后果是 `epoll_wait` 每次都被这个"幽灵事件"立即唤醒，但 `select()` 返回的就绪 key 数为 0——本该阻塞等待的 `select(timeout)` 变成**永不阻塞、立即返回 0** 的死循环。
- 外在症状：EventLoop 线程空转，`top -H` 可见**单个线程吃满一个核（100% CPU）**；jstack 显示线程 RUNNABLE 停在 `EPollArrayWrapper.epollWait`，但实际没有任何 I/O 事件可处理。业务连接还在、请求也能通，只是 CPU 白白烧掉，且该 EventLoop 上的其他 Channel 会受空转挤占、延迟抖动。

#### 解决思路：三层防御

**第一层：静态属性预防**

- `NioEventLoop.openSelector()` 在创建 Selector 前，把系统属性 `sun.nio.ch.bugLevel` 设为空串。JDK 内部按该属性的整数值（默认 2）走不同的事件处理分支，置空使其解析为 0，从而绕开有缺陷的代码路径，从源头降低触发概率。

**第二层：`selectCnt` 计数器自动检测 + `rebuildSelector()` 热替换**

- 预防不能保证根除，Netty 在 `run()` 循环里做运行时检测：每次 `select()` 返回后，若**既没有 selectedKeys、也没有任务/wakeup，且实际耗时远小于请求的超时时间**（说明 select 没有真正阻塞，属于异常提前返回），则 `selectCnt++`；只要正常阻塞满超时或有事件/任务，就重置为 1。
- 连续异常次数达到阈值 `SELECTOR_AUTO_REBUILD_THRESHOLD`（默认 **512**，可用 `io.netty.selectorAutoRebuildThreshold` 调整）即判定陷入空轮询，触发 `rebuildSelector()`：
  1. 新建一个 Selector；
  2. 遍历旧 Selector 上的所有 `SelectionKey`，把对应 Channel 连同 `interestOps` 和 attachment **原样重新注册**到新 Selector，并 cancel 旧 key；
  3. 用新 Selector 替换引用，关闭旧 Selector。
- 关键在于整个替换过程**在 EventLoop 线程自身内完成**，无需加锁、不中断任何 Channel，业务无感知——问题 Selector 里积累的脏 epoll 状态随旧实例一起被丢弃，相当于"换个干净的 epoll fd 重来"。

**第三层：IOException 兜底**

- 若 `select()` 直接抛出 `IOException`（Selector 已损坏的另一种表现形式），不走计数等待，**立即 `rebuildSelector()`** 并 `selectNow()` 一次后继续循环，保证 I/O 线程不会因异常退出或卡死。

#### 小结

- 三层递进：**能防则防（属性）→ 防不住就检测自愈（计数 + 热替换）→ 突发损坏立即重建（异常兜底）**。这套"检测 + 原地换 Selector"的思路也是 Netty 对 JDK NIO 缺陷最著名的工程化补救，后来的 native epoll transport（绕过 JDK NIO，自己实现 `epoll_wait`）则从根本上消除了该问题。

## 三、任务队列优化

### 6. MPSC 无锁队列替代 BlockingQueue

- **为什么不用 JDK 队列**：`LinkedBlockingQueue` 的 put/take 要抢锁 + Condition 唤醒，且每次入队 new 一个 Node；`ConcurrentLinkedQueue` 虽无锁但出入队都要 CAS 竞争同一批指针。EventLoop 的任务队列是每秒百万级访问的最热路径之一。
- **场景洞察**：EventLoop 任务队列的访问模式是**任意线程提交（Multi-Producer）、仅 EventLoop 自己消费（Single-Consumer）**——消费者唯一，出队根本不需要竞争。
- **JCTools MPSC 队列**（Netty 内部 shade 了一份）利用这一约束：
  - 生产者侧：CAS 推进 `producerIndex` 抢占槽位，然后普通写放入元素；
  - 消费者侧：单线程独占 `consumerIndex`，**普通读写、零 CAS**；
  - 分块数组（chunked array）结构：无界但不需一次性大数组，块内连续内存缓存友好；
  - 关键字段做 **cache line padding**，避免 producer/consumer 索引的伪共享。
- **配套的两处省开销设计**：
  - `inEventLoop()` 判断：本线程提交的任务可以直接执行/入队，跳过所有并发防护；
  - **wakeup 节流**：外部线程入队后仅当 EventLoop 正阻塞在 select 上（`wakenUp` CAS 判定）才调 `selector.wakeup()`，避免每次提交都触发一次 eventfd 写系统调用。
- JCTools 不可用（如 Unsafe 受限）时回退 `LinkedBlockingQueue`，正确性优先。

### 7. 三种队列分工明确

| 队列 | 类型 | 用途 |
|------|------|------|
| `taskQueue` | MPSC 无锁队列 | `execute()` 提交的普通任务，核心热路径 |
| `scheduledTaskQueue` | PriorityQueue | `schedule()` 定时/延时任务，按到期时间堆排序 |
| `tailTasks` | MPSC 无锁队列 | 每轮事件循环收尾时执行的清理任务 |

- **scheduledTaskQueue 为什么敢用非并发的 PriorityQueue**：它只被 EventLoop 自己的线程访问（外部 schedule 会先包装成任务投递到 taskQueue），线程封闭省掉了锁。
- **三者的协作流程**：每轮 `runAllTasks()` 前先把 scheduledTaskQueue 中已到期的任务搬运进 taskQueue，然后统一按入队顺序执行；tailTasks 在整轮 I/O + 任务处理完后执行，用于对象池延迟回收这类"不着急、但每轮都该做一次"的收尾工作。
- **时间预算控制**：`runAllTasks(timeoutNanos)` 每执行 64 个任务检查一次耗时，超出 ioRatio 预算就把剩余任务留到下一轮——保证任务执行不会无限挤占 I/O 时间。

## 四、内存管理优化

### 8. 池化内存分配器（PooledByteBufAllocator）

- **动机**：堆外内存的 `allocateDirect`/释放非常昂贵（涉及系统调用与 Cleaner），高吞吐下每请求都分配/释放不可接受；借鉴 **jemalloc** 的思路做用户态内存池。
- **Arena 分散竞争**：默认 `2 × CPU 核数` 个 Arena（受可用堆外内存上限约束），线程首次分配时轮询绑定一个 Arena，之后尽量固定——把全局锁竞争摊薄成多把局部竞争。
- **Arena → Chunk → Subpage 三级结构**：
  - **Chunk**：向 OS 申请的大块内存（默认 4MB = 8KB 页 × 2^9，4.1.75 之前为 16MB），内部用**完全二叉树 + 伙伴算法**管理页的分裂与合并；
  - **PoolSubpage**：把一个页切成等长小块，**位图**标记占用，服务 Small 级别（16B 起按 size class 量子化分级，减少内部碎片）；
  - **Normal** 级别按页 run 组合分配；超过 Chunk 的 **Huge** 请求直接走非池化分配（池化收益低、回收代价高）。
- **PoolChunkList 按使用率分组**（q000/q025/q050/q075/q100）：分配优先从"半满"的 Chunk 找，让空闲 Chunk 尽快整块归还 OS，兼顾分配命中率与内存占用。
- **PoolThreadCache 线程缓存**：release 的 small/normal 内存先回到**本线程的无锁缓存链表**，下次同级别分配直接命中，热路径完全无锁；每分配 8192 次触发 trim 防止缓存膨胀，线程退出时缓存归还 Arena。
- **为什么偏爱 Direct Memory**：JDK 的 Socket 写堆内数组时会先临时拷贝到一块堆外缓冲（堆内对象会被 GC 移动，内核无法直接引用），直接用堆外 ByteBuf 就**省掉这次 JVM 内部拷贝**；池化又消除了堆外分配本身的昂贵成本。
- ByteBuf 壳对象本身也走 Recycler 对象池复用（见第 17 节），整条分配链路稳态接近零分配。
- 4.2 起默认分配器切换为 AdaptiveByteBufAllocator（见第 9 节），Pooled 实现仍可选用。

### 9. AdaptivePoolingAllocator（自适应分配器）

- **4.2 引入并成为默认**（`AdaptiveByteBufAllocator`），目标是在多核机器上比 jemalloc 式 Arena 更简单、更快。
- **Magazine（弹匣）机制**：每个线程持有少量 magazine，每个 magazine 背后是一整块 chunk 内存 + 一个**游标（bump pointer）**。分配就是"游标前移一格"，**热路径无 CAS、无锁、无空闲链表查找**，比 Pooled 的 size class 查找 + 位图操作更短。
- **中央 ChunkAllocator 补给**：magazine 用尽时向中央分配器要新 chunk；释放的 chunk 回到中央队列循环复用，中央侧才存在少量同步开销——竞争被压缩到"换弹匣"这一低频时刻。
- **自适应**：chunk 尺寸随分配压力动态增长，小负载起步小、不浪费内存；按 size class 分组的 magazine 让小对象分配同样享受游标快路径。
- **与 Pooled 的取舍**：Pooled 的位图/伙伴算法内存利用率精细、适合内存敏感场景；Adaptive 用略高的内存冗余换取更短的分配路径与更少的元数据，适合高吞吐 RPC/网关场景。

### 10. ByteBuf 对象复用与引用计数

- **为什么不交给 GC**：堆外内存的回收依赖 Cleaner/Full GC，时机不可控，高吞吐下极易堆外 OOM；Netty 选择**显式引用计数**——`retain()` 加一、`release()` 减一，归零立即归还内存池，回收时机完全确定。
- **实现**：`AbstractReferenceCountedByteBuf` 用原子字段更新器对 `refCnt` 做 CAS，retain/release 本身无锁；归零触发 `deallocate()`，内存回 PoolThreadCache/Arena，壳对象回 Recycler。
- **所有权约定**（Netty 使用中最容易出错的部分）：
  - 入站 ByteBuf 默认归 pipeline 末端负责释放，`SimpleChannelInboundHandler` 在 `channelRead0` 返回后自动 release；
  - 中途传递/缓存消息必须 `retain()`，谁最后持有谁 release；
  - 出站 `write()` 之后所有权移交 Netty，写完成即释放，调用方不应再 release。
- **读写双指针**：`readerIndex`/`writerIndex` 分离，读写切换无需 ByteBuffer 那样 `flip()`，也天然支持"边读边写"的累积解码（配合 `ByteToMessageDecoder` 的 cumulation）。
- **泄漏检测**：`ResourceLeakDetector` 基于弱引用队列——ByteBuf 被 GC 回收时若 refCnt 仍大于 0，说明有人忘了 release，输出泄漏报告。四档强度 DISABLED/SIMPLE/ADVANCED/PARANOID，默认 SIMPLE 约 1% 采样，生产可用；PARANOID 全量跟踪并记录访问栈，仅排查时开启。

## 五、零拷贝（Zero-Copy）

### 11. FileRegion + transferTo

- **传统文件发送的代价**：`read()` + `write()` 共 **4 次数据拷贝**（磁盘 DMA → 内核页缓存 → 用户缓冲 → socket 缓冲 → 网卡 DMA）和 4 次用户态/内核态切换，其中两次 CPU 拷贝纯属过路。
- **sendfile 系统调用**：数据从页缓存直达 socket（支持 SG-DMA 的网卡上连这次拷贝也由 DMA 完成），**全程不进用户态**，拷贝降为 2 次 DMA、切换降为 2 次。
- Netty 用 `DefaultFileRegion` 封装 `FileChannel.transferTo()`，内部循环处理部分传输；`channel.writeAndFlush(fileRegion)` 即走此路径。
- **适用边界**：仅限"文件 → Socket 明文传输"。SSL/TLS 场景数据必须进用户态加密，FileRegion 失效，需改用 `ChunkedFile`/`ChunkedNioFile` 分块读写（配合 `ChunkedWriteHandler` 控制内存水位，避免大文件撑爆堆）。

### 12. CompositeByteBuf

- **场景**：协议编码常见"header + body"、"多段消息合并发送"。朴素做法是 allocate 总长大小的 buffer 再逐段 copy——一次分配 + N 次拷贝。
- **逻辑组合**：`CompositeByteBuf` 把多个 ByteBuf 作为 component 挂在内部有序表里，对外呈现为一个连续可读的 ByteBuf，**物理上零合并拷贝**；`addComponent(true, buf)` 自动推进 writerIndex。
- **定位优化**：按字节偏移找 component 用**二分查找**；`toComponentIndex0()` 对 1~2 个 component 的常见情形走 fast-path 直接判断——绝大多数协议包就是 header+body 两段。
- **配套能力**：嵌套 Composite 自动展平；必要时 `consolidate()` 真合并；出站时 `nioBuffers()` 可把各 component 直接交给 gathering write（见第 17 节），组合的优势一直延续到系统调用层。
- **代价**：跨 component 的随机访问比连续内存慢，属于"写路径省拷贝、读路径付寻址"的权衡，适合一次组装、顺序读出的报文场景。

### 13. wrappedBuffer / unwrappedBuffer

- `Unpooled.wrappedBuffer(byte[] / ByteBuffer / String...)` 把已有存储**包装**成 ByteBuf，不复制内容；多段包装则自动落到 CompositeByteBuf。
- `unwrappedBuffer()` 反向取出底层 JDK ByteBuffer，方便与 JDK/第三方 API 互操作，同样零拷贝。
- **共享语义要分清**：`slice()`/`duplicate()` 与原 buffer **共享内容和引用计数**（需要延长生命周期用 `retainedSlice()`）；`copy()` 才是真复制。误用共享视图是悬垂内存的常见来源。
- 与第 11 节的区分：sendfile 是 **OS 层零拷贝**（省内核↔用户态拷贝），wrappedBuffer/CompositeByteBuf 是 **用户态零拷贝**（省应用层合并/包装拷贝），Netty 两层都做了。

## 六、序列化与编解码优化

### 14. 泛型匹配缓存（TypeParameterMatcher）

- **问题**：`MessageToMessageDecoder<I>` 的泛型在运行期被擦除，解码器要对每条入站消息做"是不是我关心的类型"判断；若每条消息都反射解析泛型实参，开销不可接受。
- **解法**：`TypeParameterMatcher.find()` 在 handler 构造时**沿 Type 继承链解析一次实际类型实参**，结果按 (handler 类, 泛型参数名) 缓存；此后每条消息只做一次 `Class.isInstance()`，反射成本被摊销到零。
- `acceptInboundMessage()`/`acceptOutboundMessage()` 都走这个缓存 matcher，这是所有编解码器共享的热路径基础设施。
- 配合第 2 节 FastThreadLocal 缓存的 CharsetEncoder/Decoder，字符串编解码路径上的反射与对象分配都被压到最低。

### 15. Pipeline handlerState 状态机

- **结构**：`DefaultChannelPipeline` 是 `AbstractChannelHandlerContext` 的**双向链表**（HeadContext ↔ … ↔ TailContext），入站事件 head → tail 传播，出站事件 tail → head 传播；用 `ctx.write()` 而非 `channel.write()` 可以从当前节点直接出发，少走一段链表。
- **状态机**：每个 context 的 `handlerState` 走 `INIT → ADD_PENDING → ADD_COMPLETE → REMOVE_PENDING → REMOVED`，用原子字段更新器 CAS 流转。
- **为什么需要**：handler 可能被动态增删、可能绑定到别的线程池执行 `handlerAdded`；事件传播时用 `invokeHandler()`（状态是否就绪）**一次性过滤掉未初始化完成/已移除的节点**，既保证 handlerAdded 先于事件处理的时序契约，又避免每条消息重复做各种存在性判断。
- **线程模型配套**：`addLast(EventExecutorGroup, handler)` 可把慢业务 handler 卸载到独立线程池，I/O 线程只做编解码——这正是 jaws 三种传输层统一采用的 serverExecutor 卸载模式的 Netty 原生基础。

## 七、其他优化

### 16. 写操作自旋（Write Spin）

- **问题**：Socket 内核发送缓冲区满时 write 返回 0，朴素做法是立刻注册 `OP_WRITE` 等可写事件——但缓冲区往往几微秒后就腾出空间，一来一回的 epoll_ctl + 事件唤醒纯属浪费。
- **自旋策略**：`doWrite()` 循环最多尝试 `writeSpinCount`（默认 **16**，经验值）次写；写满/写完即返回，只有确实写不动才注册 OP_WRITE 挂起，等可写事件续写。
- **公平性兜底**：自旋次数耗尽但数据还没写完时，不继续霸占线程，而是把剩余写包装成任务排进队列——防止一个大流量 Channel 饿死同 EventLoop 的其他 Channel。
- **配套背压**：`ChannelOutboundBuffer` 积压超过高水位（默认 64KB）时 `isWritable()` 翻 false 并触发 `channelWritabilityChanged`，应用据此停写；回落到低水位（32KB）恢复——写自旋解决"瞬时写不动"，水位机制解决"持续写不动"。

### 17. 批量处理与对象复用

- **出站缓冲批量 flush**：`ChannelOutboundBuffer` 用链表缓存出站消息，区分已 flush/未 flush；`flush0()` 通过 `nioBuffers()` 把多个 ByteBuf 映射成 `ByteBuffer[]`，一次 **gathering write（writev）** 写出去——N 个包一次系统调用，而不是 N 次。
- **flush 合并**：`FlushConsolidationHandler` 把同一轮读循环内多次 `flush()` 合并成一次真实 flush，"读一批请求 → 写一批响应 → 一次刷出"，进一步摊薄系统调用。
- **Recycler 对象池**：ByteBuf 壳、SelectionKey 包装等高频小对象走 Recycler 复用——同线程 Stack push/pop 无锁；**跨线程归还**走 `WeakOrderQueue` 延迟转移（弱引用感知线程退出），避免生产者线程直接抢消费者线程的锁。对象池 + 内存池（第 8 节）双层复用，热路径稳态接近零 GC。

### 18. 平台相关优化

- **PlatformDependent**：启动时探测 Unsafe 可用性；可用时堆外分配走 `Unsafe.allocateMemory/freeMemory`（noCleaner 路径），**绕开 JDK Bits 配额与 Cleaner 的不确定回收**，配合引用计数实现完全自主的内存生命周期管理。
- **Native transport**：Linux epoll、macOS/BSD kqueue、以及孵化中的 io_uring，绕过 JDK NIO 直接用 JNI 调系统调用：
  - 支持**边缘触发（ET）**，减少事件重复通知；
  - 暴露 `SO_REUSEPORT`、`TCP_FASTOPEN`、`TCP_CORK` 等 JDK 不开放的 socket 选项；
  - 没有 JDK Selector 的历史 bug 与反射优化负担（第 4、5 节的问题天然不存在）；
  - 4.2 的 IoHandler 抽象让 native 后端与 NIO 共享同一套线程模型与 pipeline，切换只改一行工厂。
- 本项目按源码学习顺序引入 netty 各模块，samples 中 netty 传输层即运行在这套 NIO 优化之上。

## 总结

Netty 的高性能设计贯穿了 **线程模型 → I/O 多路复用 → 任务调度 → 内存管理 → 数据传输 → 编解码** 的每一个环节，核心思想是：

- **减少锁竞争**：MPSC 无锁队列、多 Arena/Magazine 分散、线程封闭（Channel 绑定 EventLoop）、位运算替代除法
- **消除不必要的对象创建（降低 GC）**：SelectedKeys 数组化、FastThreadLocal 内置缓存、Recycler 对象池 + ByteBuf 引用计数复用
- **避免数据拷贝**：OS 层 sendfile、用户态 CompositeByteBuf 逻辑组合、wrappedBuffer 包装、堆外内存省 JVM 内部拷贝
- **摊薄系统调用**：写自旋、gathering write、flush 合并、wakeup 节流
- **利用 OS 底层能力**：epoll/kqueue/io_uring native transport、Unsafe 直接内存操作
- **对缺陷工程化兜底**：epoll 空轮询三层防御、反射优化失败优雅回退——性能优化不以牺牲健壮性为代价