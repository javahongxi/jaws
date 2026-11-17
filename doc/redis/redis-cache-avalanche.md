# 缓存雪崩：错峰过期与多级缓存

> 主题边界：缓存三大经典问题系列之一——只讨论**大量 Key 集中失效或缓存服务整体宕机**的洪峰防护。三大问题总览见 [redis-cache-penetration.md](./redis-cache-penetration.md)，单个热点 Key 过期见 [redis-cache-breakdown.md](./redis-cache-breakdown.md)。

---

## 1. 问题定义

缓存雪崩针对**大量 Key 在同一时刻集中失效**（或缓存服务整体宕机），导致请求洪峰瞬间全部涌向 DB，容易压垮数据库并引发连锁故障。核心思路是「削峰 + 兜底」。

三层方案各管一段：随机 TTL 从源头**打散失效时间**，多级缓存**层层拦截请求**，预热与熔断限流提供**最后防线**。

## 2. 方案一：过期时间加随机值（错峰失效）

批量写入或更新缓存时，在基础过期时间上叠加一个随机偏移量，避免 Key 集中过期。这是成本最低、见效最直接的手段：

```java
// 基础 TTL 30 分钟，叠加 0~5 分钟随机抖动，将失效时间打散
int baseTtl = 30 * 60;
int randomTtl = baseTtl + ThreadLocalRandom.current().nextInt(0, 5 * 60);
cache.put(key, value, randomTtl, TimeUnit.SECONDS);
```

- **优势**：实现极简、无额外依赖，能有效打散失效峰值
- **局限**：仅缓解「集中过期」，无法应对缓存服务整体宕机
- **适用场景**：因批量 Key 同时过期引发的常规雪崩

## 3. 方案二：多级缓存架构（请求分流）

构建「本地缓存（Caffeine）→ 分布式缓存（Redis）→ DB」多级体系。即便 Redis 失效，本地缓存仍可拦截大部分请求，避免直接击穿到 DB：

```java
// L1 本地缓存：进程内，容量小、速度快，兜底 Redis 不可用
Cache<String, User> localCache = Caffeine.newBuilder()
    .maximumSize(10_000)
    .expireAfterWrite(Duration.ofMinutes(5))
    .build();

public User getUser(String id) {
    // L1：本地缓存
    User user = localCache.getIfPresent(id);
    if (user != null) {
        return user;
    }
    // L2：Redis（异常时降级，继续走 DB 而非直接失败）
    try {
        user = redisCache.get("user:" + id);
    } catch (Exception e) {
        log.warn("Redis unavailable, degrade to DB", e);
    }
    if (user != null) {
        localCache.put(id, user);
        return user;
    }
    // L3：DB 兜底，并回填各级缓存
    user = db.query(id);
    if (user != null) {
        localCache.put(id, user);
        redisCache.put("user:" + id, user, ttlWithJitter());
    }
    return user;
}
```

- **优势**：层层拦截、可用性极强，Redis 整体故障时仍能降级服务
- **局限**：本地缓存存在多实例一致性延迟，需设置较短 TTL 或引入失效通知机制
- **适用场景**：高并发核心链路、对可用性要求极高的系统

两个实现要点：Redis 访问异常时**降级继续走 DB**而非直接失败；DB 回填时 TTL 仍叠加随机抖动（`ttlWithJitter()`），与方案一配合。

## 4. 方案三：缓存预热与熔断限流（兜底防护）

- **缓存预热**：系统启动或低峰期，主动将热点数据加载到缓存，避免上线瞬间大量冷 Key 直接穿透到 DB。
- **熔断限流**：DB 侧设置连接池上限与 Sentinel/Hystrix 熔断降级，当缓存大面积失效、DB 压力超阈值时及时熔断，返回兜底数据而非拖垮整个服务。

- **优势**：从源头减少冷 Key 冲击，熔断为极端场景提供最后防线
- **局限**：预热需识别真实热点、熔断降级需设计合理的兜底返回策略
- **适用场景**：大促、秒杀等瞬时高流量，以及对整体链路稳定性要求高的生产环境

### 验收问题（读完必须能答）

- [ ] 雪崩的两种成因（集中过期 / 整体宕机）分别由哪个方案应对？
- [ ] 随机 TTL 抖动为什么只能削峰、不能兜底？
- [ ] 多级缓存中 Redis 异常时为什么要降级走 DB 而非直接失败？本地缓存的一致性代价如何缓解？
- [ ] 预热与熔断各在故障时间线的哪个阶段起作用？