# 线程安全审查报告

> 修复状态：#1～#16 已全部修复（2026-08-23），#17 记录在案、维持现状。


对 Jaws 全代码库（core、注册中心扩展、transport、spring-boot starter）中所有涉及并发原语与共享可变状态的类进行系统性审查，按 **缺锁/原子性、可见性、冗余同步、锁粒度** 四类归纳问题与修复建议。

已复核确认无问题的部分：`DefaultResponseFuture` 状态机（wait/notify + volatile state）、`NettyChannel`（volatile 快照 + 锁内状态迁移）、`AbstractTransportFactory`（锁一致）、`ConnectionLimitHandler`、`FailbackCluster` DCL、`RequestIdGenerator`、`EagerThreadPoolExecutor`、`NacosRegistry`（lock/unlock 一致）、`MetricsFilter`/`TracingFilter` 等。

## 一、缺锁 / 原子性缺陷（高）

### 1. ZookeeperRegistry.close() 锁误用 ✅已修复

- 文件：`jaws-registry-zookeeper/.../ZookeeperRegistry.java`
- 问题：`close()` 使用 `synchronized (clientLock)`，把 `ReentrantLock` 实例当内置监视器；而类中其他所有方法（`doSubscribe`/`doUnsubscribe`/`reSubscribeServices`）都用 `clientLock.lock()`。两套锁机制互不感知，`close()` 与订阅操作**完全没有互斥**——并发时可能向已清空的 `serviceListeners` 写入、泄漏 `CuratorCache`。
- 修复：改为 `clientLock.lock()` / `unlock()`（try-finally）。

### 2. JawsReference.destroy() check-then-act 非原子 ✅已修复

- 文件：`jaws-core/.../protocol/jaws/JawsReference.java`
- 问题：`if (destroyed) return; destroyed = true;` 即使字段是 `volatile` 也不保证原子。两个线程可同时通过检查，导致共享 client 被 `releaseClient` 两次、refCount 多减，连接被提前关闭。
- 修复：改用 `AtomicBoolean.compareAndSet(false, true)`。

### 3. AbstractRequestHandler.providers HashMap 并发读写 ✅已修复

- 文件：`jaws-core/.../transport/AbstractRequestHandler.java`
- 问题：`addProvider`/`removeProvider` 加了 `synchronized`，但服务端每次 RPC 都在业务线程无锁读 `providers.get()`。写端有锁读端无锁，HashMap 并发读写不安全（可能读到不一致状态）。
- 修复：改用 `ConcurrentHashMap`；`addProvider` 用 `putIfAbsent` 保留"重复即抛异常"语义，`synchronized` 可移除。

### 4. DynamicConfiguration 监听器列表迭代不安全 ✅已修复

- 文件：
  - `jaws-core/.../configcenter/LocalDynamicConfiguration.java`
  - `jaws-registry-nacos/.../NacosDynamicConfiguration.java`
  - `jaws-registry-zookeeper/.../ZookeeperDynamicConfiguration.java`
- 问题：监听器列表用 `Collections.synchronizedList` 包装，但 `notifyListeners`/`updateCacheFromRemote` 迭代时没有手动对列表加锁 → 与并发 `addListener`/`removeListener` 竞争时会抛 `ConcurrentModificationException`。
- 修复：监听器写少读多，改用 `CopyOnWriteArrayList`。

### 5. RouterChain.addRouter 读-改-写非原子 ✅已修复

- 文件：`jaws-core/.../cluster/router/RouterChain.java`
- 问题：`new ArrayList<>(routers)` → `add` → 整体替换，对 `volatile` list 的读-改-写不是原子操作，并发 `addRouter` 会丢 router。
- 修复：方法加 `synchronized`（低频调用，无性能顾虑）。

### 6. NettyServer.open() 未同步 ✅已修复

- 文件：`jaws-core/.../transport/netty/NettyServer.java`
- 问题：`close()` 是 `synchronized` 的，`open()` 却不是。`bossGroup`/`threadPoolExecutor` 的判空重建与 `cleanup()` 置空存在竞争；`stopAccept()` 也无锁读 `serverChannel`。
- 修复：`open()` 加 `synchronized`；`serverChannel` 加 `volatile`。

## 二、可见性问题（中）

### 7. AbstractLoadBalance.references 缺 volatile ✅已修复

- 文件：`jaws-core/.../cluster/loadbalance/AbstractLoadBalance.java`
- 问题：notify 线程在 `onRefresh` 中写、业务线程在每次 `select`/`selectToHolder` 中读，无任何 happens-before 保证。同层的 `AbstractDirectory.references` 已是 `volatile`，此处明显是遗漏。
- 修复：字段加 `volatile`。

### 8. AbstractCluster.references 缺 volatile ✅已修复

- 文件：`jaws-core/.../cluster/support/AbstractCluster.java`
- 问题：写端在 `synchronized onRefresh` 内，但 `destroy()`/`getReferences()`/`getInterface()`/`toString()` 无锁读；`destroy()` 与 `onRefresh` 之间也无互斥。
- 修复：字段加 `volatile`；`destroy()` 建议也加 `synchronized` 与 `onRefresh` 互斥。

### 9. NettyClient.channel / GrpcClient 连接字段缺 volatile ✅已修复

- 文件：
  - `jaws-core/.../transport/netty/NettyClient.java`（`channel` 字段）
  - `jaws-transport-grpc/.../GrpcClient.java`（`managedChannel`/`asyncStub`）
- 问题：`open()` 内写（有锁）、`request()` 无锁读。目前靠 `AbstractTransportFactory` 的锁链式发布"碰巧"安全，但十分脆弱。
- 修复：字段加 `volatile`。

### 10. ShutdownHook.instance 缺 volatile ✅已修复

- 文件：`jaws-core/.../lifecycle/ShutdownHook.java`
- 问题：写在 `synchronized registerShutdownHook`，读在无锁的 `runHook`，可见性无保证。
- 修复：字段加 `volatile`。

### 11. ShortestResponseLoadBalance.SlideWindowData offset 缺 volatile ✅已修复

- 文件：`jaws-core/.../cluster/loadbalance/ShortestResponseLoadBalance.java`
- 问题：`succeededOffset`/`succeededElapsedOffset` 由 CAS 获胜线程在 `reset()` 中写入，其他线程在 `getAverageElapsed()` 中读取，写读之间无可见性保证。
- 修复：两个字段加 `volatile`。

### 12. DefaultResponseFuture.listeners 可见性 ✅已修复

- 文件：`jaws-core/.../rpc/DefaultResponseFuture.java`
- 问题：`listeners` 在锁内写入，`notifyListeners()` 在锁外读取，无可见性保证；`attachments`/`processTime` 也是跨线程读写未同步（次要）。
- 修复：`listeners` 加 `volatile`，或在 `notifyListeners` 内锁中拷贝后锁外通知。

## 三、不必要 / 冗余的同步（低）

### 13. DirectRegistry.directUrls 多余的同步包装 ✅已修复

- 文件：`jaws-core/.../registry/DirectRegistry.java`
- 问题：列表只在构造器中写入，之后只读，`Collections.synchronizedList` 纯属多余。
- 修复：改为普通 `ArrayList`（构造完成后可包 `unmodifiableList`）。

### 14. 冗余的 volatile 标志 ✅已修复

- `ExtensionLoader.init`：所有读写都在 `synchronized checkInit()` 内，`volatile` 冗余。
- `AbstractRegistryFactory.dynamicConfigurationInitialized`：读写全程持 `lock`，`volatile` 冗余。
- 修复：移除冗余 `volatile`（保留也无害，属代码卫生）。

### 15. 冗余的 putIfAbsent 模式 ✅已修复

- `LocalRegistry.doRegister`：已在 `synchronized(registeredServices)` 内还做 `putIfAbsent`，应改 `computeIfAbsent`。
- `FailbackRegistry.addFailedSubscribed`/`addFailedUnsubscribed`：get + putIfAbsent + get 三步，按项目既定规范应统一为 `computeIfAbsent`（`LocalRegistry.doSubscribe` 同）。

## 四、锁粒度（低，供讨论）

### 16. LocalRegistry 持锁回调监听器 ✅已修复

- 文件：`jaws-core/.../registry/LocalRegistry.java`
- 问题：`doRegister`/`doUnregister` 在 `synchronized(registeredServices)` 内调用 `notifyListeners`，回调链最终走到 `RegistryDirectory.notify` → `protocol.refer` 建连接，持锁时间不可控。
- 建议：锁内完成变更并拷贝快照，锁外通知监听器。

### 17. 启动期全局锁（记录在案，可接受）

- `AbstractProtocol.export`：持 `exporterMap` 锁做 `createExporter` + `init`（含 server bind），全局串行。
- `AbstractRegistryFactory.getRegistry`：持全局 `ReentrantLock` 做 `createRegistry`（含连接注册中心）。
- 两者都是启动期低频操作，现状可接受；如未来支持动态 export 可考虑 `computeIfAbsent` 收窄锁范围。
