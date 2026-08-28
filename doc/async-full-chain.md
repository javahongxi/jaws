# Jaws 全链路异步解剖：一个 CompletableFuture 如何贯穿 RPC 的六层

> 本文基于 jaws 源码撰写。jaws 是一个核心约 2.3 万行的轻量级 RPC 框架，目标是用可读完的代码量完整呈现工业级 RPC 的核心机制。

## 0. 从一个问题开始

一个 RPC 框架为什么要关心异步？

答案藏在一次性能测试里。20 个线程打同一个 Provider，线程池 200 线程，QPS 卡在 3 万上不去。排查发现 Netty 的 event loop 线程在 `DefaultProvider.invoke()` 里被 `.get()` 阻塞了——业务方法返回 `CompletableFuture`，框架却同步等它完成，IO 线程变成了业务线程的附庸。

修复后 QPS 翻倍。但更重要的收获是：**异步不是加一个异步接口的事，它是一根从消费端代理穿到传输层再穿回 Provider 业务实现的线，任何一层断了都是同步阻塞。**

这篇文章自顶向下拆解 jaws 的异步全链路，六层，每层都有真实代码。

## 1. 消费端代理：方法签名即异步

jaws 的消费端用 JDK 动态代理把接口方法调用翻译成远程 RPC。[ReferenceInvocationHandler](https://github.com/javahongxi/jaws/blob/main/jaws-core/src/main/java/org/hongxi/jaws/proxy/ReferenceInvocationHandler.java) 的 `invoke` 方法按返回类型自动分流：

```java
public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    // ... Object 方法本地处理 ...

    DefaultRequest request = new DefaultRequest();
    request.setInterfaceName(interfaceName);
    request.setMethodName(method.getName());
    request.setParamDesc(ReflectUtils.getMethodParamDesc(method));
    request.setArguments(args);

    if (CompletableFuture.class.isAssignableFrom(method.getReturnType())) {
        return invokeAsync(request, method.getReturnType());
    }
    if (Flow.Publisher.class.isAssignableFrom(method.getReturnType())) {
        return invokeStream(request);
    }
    return invoke(request, method.getReturnType());
}
```

三条路径：返回 `CompletableFuture` 走异步，返回 `Flow.Publisher` 走流式，其余走同步。消费端不需要任何注解或配置，**接口方法的返回类型就是调用模式的声明**。

这意味着同一个服务接口可以混合使用三种模式：

```java
public interface DemoService {
    String hello(String name);                              // 同步
    CompletableFuture<String> helloAsync(String name);      // 异步
    Flow.Publisher<String> helloStream(String name);        // 流式
}
```

`invokeAsync` 的实现值得细看——它不是简单地"发请求然后返回 Future"，而是把 jaws 内部的 `DefaultResponseFuture`（基于 `lock.wait/notifyAll` 的回调机制）桥接成 `CompletableFuture`：

```java
CompletableFuture<Object> invokeAsync(Request request, Class<?> returnType) {
    RpcContext.getContext().putAttribute(JawsConstants.ASYNC_FLAG, true);
    // ... 设置 attachments ...

    CompletableFuture<Object> resultFuture = new CompletableFuture<>();

    for (Cluster<T> cluster : clusters) {
        try {
            Response response = cluster.call(request);
            if (response instanceof DefaultResponseFuture responseFuture) {
                responseFuture.setReturnType(returnType);
                responseFuture.addListener(future -> {
                    if (future.isSuccess()) {
                        resultFuture.complete(future.getValue());
                    } else {
                        resultFuture.completeExceptionally(future.getException());
                    }
                });
            } else {
                // 同步响应（如 injvm 直调）
                if (response.getException() != null) {
                    resultFuture.completeExceptionally(response.getException());
                } else {
                    resultFuture.complete(response.getValue());
                }
            }
            return resultFuture;
        } catch (RuntimeException e) {
            resultFuture.completeExceptionally(e);
            return resultFuture;
        }
    }
    // ...
}
```

关键细节：`cluster.call(request)` 返回的是 `DefaultResponseFuture`——它此时还没有值，但已经注册了超时调度。`addListener` 在响应到达或超时时被回调，把结果传递给 `CompletableFuture`。消费端业务代码拿到 `CompletableFuture` 后可以自由地 `.thenApply`、`.whenComplete`，完全不阻塞。

## 2. 传输层契约：MessageHandler 是一等公民的异步

很多 RPC 框架的传输层接口是同步的——收到消息、处理、返回结果，异步是在上层包装出来的。jaws 走了一条不同的路：**传输层的 `MessageHandler` 接口本身就是异步的**。

```java
public interface MessageHandler {
    CompletableFuture<Object> handleAsync(Channel channel, Object message);

    default Flow.Publisher<Object> handleStream(Channel channel, Object message) {
        throw new UnsupportedOperationException("Streaming not supported by this handler");
    }
}
```

`handleAsync` 返回 `CompletableFuture<Object>`，不是 `void`，不是同步的 `Object`。这个设计决策的意义是：**传输层不需要知道上层是同步还是异步，它只需要把 CompletableFuture 链传递下去**。

这个接口经历过一次重构。最初 `MessageHandler` 有一个同步的 `handle` 方法和一个 `handleAsync` 方法，`NettyChannelHandler` 需要强转 `MessageHandler` 为特定实现才能调异步方法。重构后直接砍掉同步方法，接口只剩 `handleAsync` 一个核心方法——**当异步是一等公民时，同步接口反而是多余的**。

实现端，`ProviderMessageHandler` 按请求类型分发：

```java
public CompletableFuture<Object> handleAsync(Channel channel, Object message) {
    if (message instanceof Request request) {
        boolean isGeneric = "true".equals(request.getAttachments().get("$generic"));
        if (isGeneric) {
            return genericHandler.handleAsync(channel, message);
        }
    }
    return normalHandler.handleAsync(channel, message);
}
```

`AbstractRequestHandler` 的 `handleAsync` 做 Provider 查找和方法解析，然后调 `doHandleAsync`。以 `NormalRequestHandler` 为例：

```java
protected CompletableFuture<Object> doHandleAsync(Request request, Provider<?> provider, Method method) {
    return callAsync(request, provider).thenApply(response -> {
        response.setSerializationNumber(request.getSerializationNumber());
        return response;
    });
}
```

`callAsync` 调 `provider.callAsync(request)`，拿到 `CompletableFuture<Response>`，用 `thenApply` 链式地补上序列化号。全程没有 `.get()`，没有阻塞。

## 3. Provider 端：业务方法可以是异步的

[DefaultProvider.invoke()](https://github.com/javahongxi/jaws/blob/main/jaws-core/src/main/java/org/hongxi/jaws/rpc/DefaultProvider.java) 返回 `CompletableFuture<Response>`，是 Provider 端异步的核心。它处理两种情况：业务方法返回普通值，或业务方法返回 `CompletableFuture`。

```java
public CompletableFuture<Response> invoke(Request request) {
    DefaultResponse response = new DefaultResponse();
    Method method = lookupMethod(request.getMethodName(), request.getParamDesc());

    if (method == null) {
        response.setException(new JawsServiceException("Service method not found: ..."));
        return CompletableFuture.completedFuture(response);
    }

    try {
        Object value = method.invoke(ref, request.getArguments());

        if (value instanceof CompletableFuture<?> future) {
            // 业务方法是异步的：链式组合，支持超时
            long timeout = this.url.getMethodParameter(
                    request.getMethodName(), request.getParamDesc(),
                    UrlParam.Transport.REQUEST_TIMEOUT.getName(),
                    UrlParam.Transport.REQUEST_TIMEOUT.intValue());
            if (timeout > 0) {
                future = future.orTimeout(timeout, TimeUnit.MILLISECONDS);
            }
            return future.handle((result, throwable) -> {
                DefaultResponse asyncResponse = new DefaultResponse();
                asyncResponse.setAttachments(request.getAttachments());
                if (throwable != null) {
                    Throwable cause = throwable instanceof CompletionException
                            ? throwable.getCause() : throwable;
                    if (cause instanceof TimeoutException) {
                        asyncResponse.setException(new JawsServiceException(
                                "provider async call timeout: ..."));
                    } else if (cause instanceof Exception ex) {
                        asyncResponse.setException(new JawsBizException(
                                "provider async call failed", ...));
                    }
                } else {
                    asyncResponse.setValue(result);
                }
                return asyncResponse;
            });
        }

        // 业务方法是同步的：直接包装
        response.setValue(value);
    } catch (Exception e) {
        response.setException(new JawsBizException("provider call failed", ...));
    }

    response.setAttachments(request.getAttachments());
    return CompletableFuture.completedFuture(response);
}
```

三个设计点值得注意：

**自动适配**。业务实现返回 `CompletableFuture` 就走 `.handle()` 链式组合，返回普通值就 `completedFuture` 包装。Provider 不需要知道业务是同步还是异步——`invoke()` 的返回类型始终是 `CompletableFuture<Response>`，上层统一处理。

**超时用 `orTimeout`**。Provider 端的异步超时不是另起一个定时器，而是用 `CompletableFuture.orTimeout()` 链在业务 Future 上——超时发生时自动以 `TimeoutException` 完成，后续的 `.handle()` 统一处理异常。一行代码搞定超时 + 异常 + 结果三种情况。

**同步方法零开销**。如果业务方法返回普通值（绝大多数场景），走的是 `CompletableFuture.completedFuture(response)` 路径——这是一个已经完成的 Future，上层的 `.whenComplete` 会同步执行，没有任何异步开销。

`AbstractProvider` 的桥接也很干净：

```java
public abstract class AbstractProvider<T> implements Provider<T> {
    protected abstract CompletableFuture<Response> invoke(Request request);

    @Override
    public Response call(Request request) {
        return invoke(request).join();  // 同步桥接：仅在需要时阻塞
    }

    @Override
    public CompletableFuture<Response> callAsync(Request request) {
        return invoke(request);         // 异步直通：零开销
    }
}
```

`call()` 用 `.join()` 阻塞等待——这是给 Filter 链和 Cluster 层用的，它们在消费端需要拿到结果值；`callAsync()` 直接透传 Future——这是给 Provider 端的 `NettyChannelHandler` 用的，它不需要阻塞。

## 4. Netty IO 线程零阻塞：whenComplete 写回响应

前面三层的异步最终要在 Netty 的 IO 线程上落地。[NettyChannelHandler.processRequest()](https://github.com/javahongxi/jaws/blob/main/jaws-core/src/main/java/org/hongxi/jaws/transport/netty/NettyChannelHandler.java) 是异步链的"最后一公里"：

```java
private void processRequest(ChannelHandlerContext ctx, Request request) {
    request.setAttachment(UrlParam.Server.HOST.getName(),
            NetUtils.getHostName(ctx.channel().remoteAddress()));
    final long processStartTime = System.currentTimeMillis();

    if (channel instanceof NettyServer nettyServer) {
        nettyServer.getActiveRequests().incrementAndGet();
    }
    RpcContext.init(request);

    messageHandler.handleAsync(channel, request).whenComplete((res, throwable) -> {
        try {
            RpcContext.init(request);
            DefaultResponse response;
            if (throwable != null) {
                response = RpcUtils.buildErrorResponse(request,
                        new JawsServiceException("process request failed: "
                                + throwable.getMessage()));
            } else if (res instanceof DefaultResponse dr) {
                response = dr;
            } else if (res instanceof Response r) {
                response = new DefaultResponse(r);
            } else {
                response = new DefaultResponse(res);
            }
            response.setRequestId(request.getRequestId());
            response.setProcessTime(System.currentTimeMillis() - processStartTime);
            sendResponse(ctx, response);
        } finally {
            if (channel instanceof NettyServer nettyServer) {
                nettyServer.getActiveRequests().decrementAndGet();
            }
            RpcContext.destroy();
        }
    });
}
```

`handleAsync` 返回 `CompletableFuture`，`.whenComplete` 在异步处理完成后（可能在业务线程池，可能在 event loop，取决于业务方法是同步还是异步）执行响应编码和发送。

**IO 线程不等待**。`processRequest` 方法本身在调用 `handleAsync` 后立即返回，Netty event loop 线程立刻去处理下一个事件。响应写回发生在 `whenComplete` 的回调里——这个回调可能在另一个线程上执行，但这没关系，`sendResponse` 里的 `ctx.channel().writeAndFlush(buf)` 是线程安全的（Netty 会自动调度到 event loop 执行实际写操作）。

服务端线程池的集成也值得看。`channelRead` 里，请求先提交到 `serverExecutor` 业务线程池，再调 `processRequest`：

```java
public void channelRead(ChannelHandlerContext ctx, Object msg) {
    if (msg instanceof DecodedFrame frame) {
        try {
            if (serverExecutor != null) {
                try {
                    frame.data().retain();
                    serverExecutor.execute(() -> {
                        try {
                            processFrame(ctx, frame);
                        } finally {
                            frame.data().release();
                        }
                    });
                } catch (RejectedExecutionException rejectException) {
                    frame.data().release();
                    rejectFrame(ctx, frame);  // 线程池满，立即拒绝
                }
            } else {
                processFrame(ctx, frame);
            }
        } finally {
            frame.data().release();
        }
    }
}
```

线程池满时直接返回错误响应，不排队不等待——这和 Dubbo 的 `RejectedExecutionException` 处理策略一致。但 jaws 更简洁的地方在于：即使请求进了线程池，`processRequest` 里的 `handleAsync` + `whenComplete` 也保证了**线程池线程不会被阻塞等待业务完成**——如果业务方法返回 `CompletableFuture`，线程池线程在 `handleAsync` 返回后就释放了，`whenComplete` 回调在 CompletableFuture 完成的线程上执行。

## 5. 消费端响应回调：DefaultResponseFuture 与超时调度

请求从消费端发出后，响应在 Netty event loop 上异步到达。[NettyClient](https://github.com/javahongxi/jaws/blob/main/jaws-core/src/main/java/org/hongxi/jaws/transport/netty/NettyClient.java) 在初始化 pipeline 时注册了一个 lambda 作为消息处理器：

```java
pipeline.addLast("handler", new NettyChannelHandler(NettyClient.this,
    (Channel channel, Object message) -> {
        Response response = (Response) message;
        ResponseFuture responseFuture = NettyClient.this.removeCallback(response.getRequestId());

        if (responseFuture == null) {
            log.warn("received response, but no responseFuture found, requestId={}",
                    response.getRequestId());
            return CompletableFuture.completedFuture(null);
        }
        if (response.getException() != null) {
            responseFuture.onFailure(response);
        } else {
            responseFuture.onSuccess(response);
        }
        return CompletableFuture.completedFuture(null);
    }));
```

`removeCallback` 按 `requestId` 从 `callbackMap` 中找到对应的 `DefaultResponseFuture`，调 `onSuccess` 或 `onFailure` 完成它。`DefaultResponseFuture` 内部通过 `lock.notifyAll()` 唤醒阻塞的 `getValue()` 调用，同时通知所有注册的 `FutureListener`。

超时调度在 [AbstractClient](https://github.com/javahongxi/jaws/blob/main/jaws-core/src/main/java/org/hongxi/jaws/transport/AbstractClient.java) 里统一管理：

```java
private static final HashedWheelTimer timeoutTimer = new HashedWheelTimer(
        new io.netty.util.concurrent.DefaultThreadFactory("jaws-client-timeout", true),
        30, TimeUnit.MILLISECONDS);

public void registerCallback(long requestId, ResponseFuture responseFuture) {
    if (callbackMap.size() >= MAX_INFLIGHT_REQUESTS) {
        throw new JawsServiceException("exceeded max concurrent requests, rejected");
    }
    callbackMap.put(requestId, responseFuture);

    int timeout = responseFuture.getTimeout();
    if (timeout > 0) {
        Timeout timerTimeout = timeoutTimer.newTimeout(t -> {
            ResponseFuture future = callbackMap.remove(requestId);
            if (future != null) {
                timeoutMap.remove(requestId);
                future.cancel();
            }
        }, timeout, TimeUnit.MILLISECONDS);
        timeoutMap.put(requestId, timerTimeout);
    }
}
```

三个设计点：

**HashedWheelTimer 而不是 ScheduledThreadPoolExecutor**。RPC 客户端在途请求成千上万时，`ScheduledThreadPoolExecutor` 的堆操作是 O(log n) 且有全局锁竞争；时间轮按 tick 环形槽管理，插入 O(1)。精度受 tick 间隔（30ms）限制，但对 RPC 超时这种百毫秒级的场景足够。

**回调移除的三种触发**。注释写得很清楚：`response received / timeout task cancels / close()`。三者共享同一个不变量——`removeCallback` 是幂等的，`ConcurrentMap.remove` 保证只有一个触发者能拿到 Future 并完成它。

**过载保护**。`MAX_INFLIGHT_REQUESTS = 20000`，超过直接拒绝，防 OOM。这个保护必须在基类做——TCP 和 HTTP/2 两个客户端共享同一套记账逻辑，放在基类是最不容易漏的位置。

## 6. 容错层：FailbackCluster 异步重试

异步不仅体现在单次调用上，还体现在容错策略上。[FailbackCluster](https://github.com/javahongxi/jaws/blob/main/jaws-core/src/main/java/org/hongxi/jaws/cluster/support/FailbackCluster.java) 把失败的请求放入队列，由后台线程定时重试：

```java
@Extension("failback")
public class FailbackCluster<T> extends AbstractCluster<T> {
    private static final ScheduledExecutorService RETRY_EXECUTOR =
            Executors.newScheduledThreadPool(1, r -> {
                Thread t = new Thread(r, "jaws-failback-retry");
                t.setDaemon(true);
                return t;
            });

    private final Queue<FailbackTask<T>> failedTasks = new ConcurrentLinkedQueue<>();
    private volatile boolean retryScheduled = false;

    @Override
    public Response call(Request request) {
        Reference<T> refer = loadBalance.select(request);
        try {
            RpcContext.getContext().setServerUrl(refer.getUrl());
            return refer.call(request);
        } catch (RuntimeException e) {
            if (ExceptionUtils.isBizException(e)) {
                throw e;  // 业务异常不重试
            }
            log.warn("FailbackCluster call failed, recording for retry: {}", request, e);
            addTask(request);
            DefaultResponse response = new DefaultResponse(request.getRequestId());
            response.setException(e);
            return response;
        }
    }

    private void ensureRetryScheduled() {
        if (!retryScheduled) {
            synchronized (this) {
                if (!retryScheduled) {
                    retryScheduled = true;
                    int period = url.getIntParameter(UrlParam.Registry.FAILBACK_PERIOD);
                    RETRY_EXECUTOR.scheduleAtFixedRate(
                            this::retry, period, period, TimeUnit.MILLISECONDS);
                }
            }
        }
    }
}
```

失败请求不阻塞调用方——立即返回带异常的 Response，后台 `ScheduledExecutorService` 按固定间隔重试。适合通知推送、日志上报等"最终一致"场景。

同样的模式出现在 [FailbackRegistry](https://github.com/javahongxi/jaws/blob/main/jaws-core/src/main/java/org/hongxi/jaws/registry/FailbackRegistry.java)：注册/注销/订阅失败时放入失败队列，定时重试。**异步重试是 jaws 容错体系的通用模式**。

## 7. 全链路数据流：一次异步调用的一生

把六层串起来，一次异步 RPC 调用的完整数据流：

```
Consumer 业务线程
  │
  ├─ 1. ReferenceInvocationHandler.invoke()
  │     检测返回类型为 CompletableFuture → invokeAsync()
  │     创建 CompletableFuture<Object> resultFuture
  │     调 cluster.call(request) → 返回 DefaultResponseFuture（未完成）
  │     注册 FutureListener → 桥接到 resultFuture
  │     立即返回 resultFuture 给业务代码
  │
  ├─ 2. NettyClient.channel.request()
  │     编码请求 → ByteBuf → Netty writeAndFlush
  │     registerCallback(requestId, responseFuture) + 超时调度
  │
  ╰──── 网络传输 ────→

Server Netty Event Loop
  │
  ├─ 3. NettyChannelHandler.channelRead()
  │     提交到 serverExecutor 业务线程池
  │
  ├─ 4. processRequest()
  │     messageHandler.handleAsync(channel, request)
  │       → ProviderMessageHandler → NormalRequestHandler
  │         → provider.callAsync(request)
  │           → DefaultProvider.invoke(request)
  │             → method.invoke(ref, args)
  │             → 如果返回 CompletableFuture → .orTimeout().handle()
  │             → 如果返回普通值 → completedFuture(response)
  │     .whenComplete((res, throwable) -> {
  │         sendResponse(ctx, response);  // 编码响应 → ByteBuf → writeAndFlush
  │         activeRequests.decrementAndGet();
  │         RpcContext.destroy();
  │     })
  │
  ╰──── 网络传输 ────→

Consumer Netty Event Loop
  │
  ├─ 5. NettyChannelHandler.processFrame()
  │     解码响应 → messageHandler.handleAsync(channel, response)
  │       → lambda: removeCallback(requestId)
  │         → responseFuture.onSuccess(response)
  │           → lock.notifyAll() 唤醒等待线程
  │           → 通知 FutureListener
  │
  ├─ 6. FutureListener.operationComplete()
  │     resultFuture.complete(future.getValue())
  │
  ╰─ Consumer 业务线程
       CompletableFuture 完成
       .thenApply / .whenComplete 回调执行
```

六层之间，**没有任何一层在 IO 线程上执行 `.get()` 或 `.join()`**。同步等待只发生在消费端业务线程主动调 `future.get()` 的场景——但异步模式下，业务代码拿到的是 `CompletableFuture`，根本不需要 `get()`。

## 8. 与 Dubbo 异步模型的对比

Dubbo 的异步演进经历了三个阶段：

| 阶段 | 机制 | 问题 |
|------|------|------|
| Dubbo 2.x | `async=true` 配置 + `RpcResult` 包装 | 异步是配置项而不是类型系统的一部分，消费端需要额外配置 |
| Dubbo 3.x 早期 | `AsyncRpcResult` 返回 `CompletableFuture` | Provider 端全链路异步，但消费端仍需通过 `RpcContext.getFuture()` 获取 |
| Dubbo 3.x 成熟 | 接口方法返回 `CompletableFuture` 即异步 | 与 jaws 同构：方法签名即调用模式 |

jaws 直接跳到了最终形态：**消费端不需要任何配置或注解，接口方法的返回类型就是调用模式的声明**。这和 Dubbo 3.x 的最终方向一致，但 jaws 的实现路径更短——因为没有历史包袱。

Provider 端的差异更明显。Dubbo 的 `AsyncRpcResult` 是一个专门的类，内部持有 `CompletableFuture<Object>` 并处理同步/异步两种完成路径；jaws 的 `DefaultProvider.invoke()` 直接返回 `CompletableFuture<Response>`，不引入额外的包装类。**当 JDK 已经提供了足够好的异步原语时，框架应该直接使用它，而不是发明自己的**。

传输层方面，Dubbo 的 `ExchangeHandler.reply()` 返回 `CompletableFuture<Object>`，与 jaws 的 `MessageHandler.handleAsync()` 设计同构。两者都认识到：**传输层接口必须是异步的，否则上层异步会被 IO 线程的同步调用链打断**。

## 9. 写在最后：异步是一种架构决策，不是一个 API

回顾 jaws 的异步全链路，最深刻的体会是：**异步不是加一个 `async` 关键字或返回一个 `Future` 的事，它是一个贯穿消费端代理、Cluster 层、传输层、Provider 端、业务实现的架构决策**。任何一层用了同步模型，整条链路的异步就被打断。

jaws 的选择是：

- **消费端**：返回类型即调用模式，`CompletableFuture` = 异步，`Flow.Publisher` = 流式，其余 = 同步
- **传输层**：`MessageHandler.handleAsync()` 是一等公民的异步契约
- **Provider 端**：`invoke()` 返回 `CompletableFuture<Response>`，自动适配同步/异步业务方法
- **IO 层**：`whenComplete` 写回响应，event loop 零阻塞
- **响应回调**：`DefaultResponseFuture` + `HashedWheelTimer` 超时调度，传输无关
- **容错层**：`FailbackCluster` / `FailbackRegistry` 异步重试

六层，每层都是异步的，所以全链路才是异步的。

> jaws 源码：[github.com/javahongxi/jaws](https://github.com/javahongxi/jaws)（核心约 2.3 万行，欢迎 star 交流）
