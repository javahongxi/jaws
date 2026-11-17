# 缓存击穿：互斥重建与逻辑过期

> 主题边界：缓存三大经典问题系列之一——只讨论**单个热点 Key 过期瞬间**的并发冲击。三大问题总览见 [redis-cache-penetration.md](./redis-cache-penetration.md)，大量 Key 集中失效见 [redis-cache-avalanche.md](./redis-cache-avalanche.md)。

---

## 1. 问题定义

缓存击穿针对**单个热点 Key**：其过期瞬间，海量并发请求同时穿透缓存落到 DB，造成瞬时压力尖峰。核心思路是「只让一个请求去重建缓存，其余请求等待或返回旧值」。

两种方案的分野在于**一致性换可用性**：互斥锁让其余请求等待，保证读到最新值；逻辑过期让其余请求拿旧值，保证永不阻塞。

## 2. 方案一：互斥锁（强一致性首选）

借助 Redisson 分布式锁，抢到锁的线程查 DB 并回写缓存，其余线程阻塞等待后重试命中缓存。以热 Key 重建为例：

```java
public User getUserById(String id) {
    User user = cache.get("user:" + id);
    if (user != null) {
        return user;
    }
    // 缓存未命中，尝试获取分布式锁（仅锁住重建过程，读缓存不加锁）
    RLock lock = redissonClient.getLock("lock:user:" + id);
    lock.lock();
    try {
        // 双重检查：可能已被其他线程回写
        user = cache.get("user:" + id);
        if (user == null) {
            user = db.query(id);            // 查 DB
            if (user != null) {
                cache.put("user:" + id, user, 30, TimeUnit.MINUTES);
            }
        }
        return user;
    } finally {
        lock.unlock();
    }
}
```

- **优势**：实现简单、数据强一致，缓存中始终是最新值
- **局限**：其余线程需阻塞等待，存在一定耗时与潜在死锁风险（需设置锁超时）
- **适用场景**：对一致性要求高、可接受短暂等待的热点 Key 重建

两个实现要点：**读缓存不加锁**，锁只覆盖重建临界区；拿锁后**双重检查**，避免重复查 DB 回写。

## 3. 方案二：逻辑过期（高并发可用性首选）

不给 Key 设置真实的 Redis TTL，而是在 Value 中内嵌一个「逻辑过期时间」。发现逻辑过期后，由抢到锁的线程**异步**重建缓存，当前请求直接返回旧值，不阻塞。

```java
// Value 包装：数据 + 逻辑过期时间戳
@Data
@AllArgsConstructor
public class CacheData<T> {
    private T data;
    private long expireAt;   // 逻辑过期时间（非 Redis TTL）
}

public User getUserById(String id) {
    CacheData<User> wrapper = cache.get("user:" + id);
    if (wrapper == null) {
        return loadAndCache(id);            // 首次无缓存，同步加载
    }
    if (System.currentTimeMillis() < wrapper.getExpireAt()) {
        return wrapper.getData();           // 未过期，直接返回
    }
    // 已逻辑过期：尝试抢锁，抢到则异步重建，本次仍返回旧值
    RLock lock = redissonClient.getLock("rebuild:user:" + id);
    if (lock.tryLock()) {
        try {
            asyncExecutor.submit(() -> {
                try { loadAndCache(id); }    // 后台重建
                finally { lock.unlock(); }
            });
        } catch (RejectedExecutionException e) {
            lock.unlock();                   // 线程池满则释放锁，交下次请求重试
        }
    }
    return wrapper.getData();               // 不阻塞，返回旧值
}
```

- **优势**：请求永不阻塞、吞吐量高，彻底消除瞬时 DB 尖峰
- **局限**：重建期间会短暂返回旧数据（最终一致）；需额外维护逻辑过期字段与线程池
- **适用场景**：读多写少、可接受短暂不一致的超高并发热点（如秒杀、热门商品）

两个实现要点：`tryLock()` 非阻塞抢锁，抢不到直接返回旧值；异步任务提交失败（线程池满）必须**释放锁**，否则重建永远无人接手。由于 Key 无真实 TTL，逻辑过期数据常驻内存，需配合预热与容量规划。

## 4. 选型对比

| 维度 | 互斥锁 | 逻辑过期 |
|---|---|---|
| 一致性 | 强一致，始终最新值 | 最终一致，重建期返回旧值 |
| 可用性 | 其余线程阻塞等待 | 请求永不阻塞 |
| 实现复杂度 | 低 | 需逻辑过期字段 + 异步线程池 |
| 典型场景 | 一致性敏感的热点重建 | 秒杀、热门商品等超高并发读 |

### 验收问题（读完必须能答）

- [ ] 击穿与穿透的区别是什么？为什么布隆过滤器解决不了击穿？
- [ ] 互斥锁方案中，为什么读缓存不加锁、拿锁后还要双重检查？
- [ ] 逻辑过期方案为什么不设置真实 TTL？异步提交失败时为什么必须释放锁？
- [ ] 两种方案各牺牲了什么、换来了什么？