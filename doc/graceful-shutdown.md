# 优雅停机

Jaws 支持四阶段优雅停机，确保服务下线时不中断在途请求，实现零损伤发布。

## 停机阶段

```
Phase 1: stopAccept()              关闭 ServerChannel，不再接收新连接
Phase 2: awaitInactiveRequests()   等待在途请求处理完成（默认超时 10s）
Phase 3: unregister()              从注册中心注销服务
Phase 4: unexport() → destroy()    关闭连接、释放 EventLoopGroup 和线程池
```

## 配置

通过 `gracefulShutdownTimeout` URL 参数可配置 Phase 2 最大等待时间（默认 10000ms）。

## 验证步骤

```bash
# 1. 启动 ZooKeeper（如未运行）
# 2. 启动 Provider
./run-sample.sh provider

# 3. 另开终端，运行 Consumer
./run-sample.sh consumer

# 4. 在 Provider 运行期间发送 SIGTERM（不要用 kill -9）
jps | grep SampleProvider   # 找到 PID
kill -TERM <PID>

# 5. 观察 Provider 日志，应依次输出：
# [GracefulShutdown] Phase 1: Stop accepting new requests
# [GracefulShutdown] Phase 2: Waiting for in-flight requests to complete
# All in-flight requests completed before shutdown
# [GracefulShutdown] Phase 3: Unregister from registry
# [GracefulShutdown] Phase 4: Close connections and release resources
# [GracefulShutdown] Graceful shutdown completed
```
