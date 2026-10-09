# 优雅停机

Jaws 支持四阶段优雅停机，确保服务下线时不中断在途请求，实现零损伤发布。

## 停机阶段

```
Phase 1: unregister()              从注册中心注销服务
Phase 2: stopAccept()              关闭 ServerChannel + 发 GOAWAY，并对已建连接上的新请求关门
Phase 3: drainInflightRequests()   等待在途请求处理完成（默认超时 10s）
Phase 4: unexport() → destroy()    关闭连接、释放 EventLoopGroup 和线程池
```

注销排在最前，是为了让消费端尽早开始刷新地址列表；但注销到消费端生效之间有传播延迟，这段延迟由 Phase 2 覆盖。

`stopAccept()` 关掉的只是监听 socket，已建立的连接不受影响——wire/jaws-over-h2 会额外发 GOAWAY，那也只是「请求」客户端别再用。真正保证 Phase 3 等的集合只减不增，是服务端自己那道接受闸：闸门落下后，新到达的请求不再进入 `inflightRequests`，而是立刻被拒绝——wire 回 `UNAVAILABLE`，jaws-over-h2 回 503，jaws 二进制协议回 `SERVICE_SHUTDOWN`（40004）。

二进制协议没有 GOAWAY 这类连接级信号，`SERVICE_SHUTDOWN` 就是「这条连接到此为止」的唯一通知。客户端收到它即丢弃当前连接，下一次调用走 transport 级重连重新挑节点；`FailoverCluster` 也把这个码的服务端拒绝响应算作可换节点的一次失败。之所以要单独一个码而不是复用 `SERVICE_REJECT`：后者同时表示「线程池满了，你等一下再来」，那种情况下重连只会把刚落地的请求推向同一台已经饱和的机器。停机拒绝可以安全换节点重试，是因为闸门挡在业务派发之前，这一单确定没有在任何节点上执行过。

## 配置

通过 `gracefulShutdownTimeout` URL 参数可配置 Phase 3 最大等待时间（默认 10000ms）。

## 验证步骤

```bash
# 1. 启动 ZooKeeper（如未运行）
# 2. 启动 Provider
./run-sample.sh provider

# 3. 另开终端，运行 Consumer
./run-sample.sh consumer

# 4. 在 Provider 运行期间发送 SIGTERM（不要用 kill -9）
jps | grep ZkProvider   # 找到 PID
kill -TERM <PID>

# 5. 观察 Provider 日志，应依次输出：
# [GracefulShutdown] Phase 1: Unregister from registry
# [GracefulShutdown] Phase 2: Stop accepting new requests
# [GracefulShutdown] Phase 3: Waiting for in-flight requests to complete
# All in-flight requests completed before shutdown
# [GracefulShutdown] Phase 4: Close connections and release resources
# [GracefulShutdown] Graceful shutdown completed
```
