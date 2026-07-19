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
