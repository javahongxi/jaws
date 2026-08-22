# 性能测试

项目提供 `run-sample.sh` 脚本统一管理基准测试：

## 快速运行

```bash
# injvm 协议基准（框架纯开销）
./run-sample.sh bench-injvm

# jaws + Netty 网络传输基准
./run-sample.sh bench-jaws

# 自定义参数
THREADS=8 WARMUP=5 DURATION=20 ./run-sample.sh bench-jaws

# 切换序列化方式
SERIALIZATION=hessian2 ./run-sample.sh bench-jaws

# 模拟业务耗时（Provider 端每次调用 sleep 5ms）
SLEEP=5 ./run-sample.sh bench-jaws
```

## Benchmark 环境变量

| 变量          | 说明           | 默认值      | 适用范围      |
|-------------|--------------|-----------|-----------|
| `THREADS`   | 并发线程数        | 4         | bench-injvm / bench-jaws |
| `WARMUP`    | 预热秒数         | 5         | bench-injvm / bench-jaws |
| `DURATION`  | 测量秒数         | 10        | bench-injvm / bench-jaws |
| `PORT`      | jaws 协议端口    | 10010     | 仅 bench-jaws |
| `SERIALIZATION` | 序列化方式（fastjson2 / hessian2） | fastjson2 | 仅 bench-jaws |
| `SLEEP`       | Provider 端模拟业务耗时（毫秒）      | 0         | bench-injvm / bench-jaws |

## 参数选择建议

| 场景 | 线程数 | 测量秒数 | 说明 |
|------|--------|---------|------|
| 快速验证 | 4 | 10 | 默认值，确认功能正常 |
| 常规压测 | 8~16 | 10 | 观察中等并发下的表现 |
| 极限吞吐 | 32~64 | 20 | 探索 QPS 天花板 |
| 模拟业务（有 sleep） | 8~16 | 20~30 | 单次调用慢，需更多时间积累样本 |

> QPS = 总调用次数 / 测量秒数。并发线程数决定"同时有多少请求在飞"，线程越多 QPS 越高，直到达到系统瓶颈。

## 性能基线（2026-08-23）

环境：macOS aarch64，8 核，单 JVM（provider + consumer 同进程），jaws + netty，fastjson2，DURATION=20s。

| 线程数 | QPS | 说明 |
|------|-------|---------|
| 4 | 62,314 | 优化前约 5.7 万，优化后 +9% |
| 10 | 93,694 | 已达峰值的 93% |
| 16 | 97,363 | 与 20 线程仅差 3%，无锁竞争迹象 |
| 20 | 100,408 | 峰值，约 12.5k QPS/核 |
| 25 | 98,785 | 超配核数，上下文切换损耗 |

结论：8 核下吞吐天花板约 10 万 QPS，最佳并发点在 16~20 线程；扩展曲线单调爬升后平稳回落，属于干净的 CPU 饱和形态。

长跑确认（20 线程，DURATION=60s）：QPS 98,202，共 5,892,122 次调用，0 错误（含返回值校验）；Avg 203 us，P99 388 us，P99.9 749 us，Max 6.7 ms。长时间压测无吞吐衰减、无串响应、无框架异常。
