# 连接预热 / Warm-up

新启动的 Provider 逐步增加权重，避免 JIT 未充分编译、缓存未热时被打爆。

## 工作原理

- Provider 注册时自动写入启动时间戳
- Consumer 端 LoadBalance 根据运行时长线性加权（0 → 满权重）
- 运行时长 ≥ warmup 后达到满权重

## 支持的负载均衡策略

Random / RoundRobin / LeastActive / ShortestResponse

> ConsistentHash 不支持 warmup（哈希策略不适用权重）

## 配置

默认预热时长 10 分钟，可通过 `warmup` URL 参数自定义（毫秒）。
