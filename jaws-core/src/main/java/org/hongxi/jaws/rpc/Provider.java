package org.hongxi.jaws.rpc;

import org.hongxi.jaws.common.extension.Scope;
import org.hongxi.jaws.common.extension.Spi;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

/**
 * Server-side invocation abstraction wrapping a service implementation (roughly the
 * provider-side {@code Invoker} in Dubbo). Resolves request method signatures via
 * {@link #lookupMethod(String, String)} and exposes the backing implementation through
 * {@link #getImpl()}. The default {@link #callAsync(Request)} bridges synchronous
 * {@link #call(Request)} results into a {@link CompletableFuture}.
 * Registered as a prototype-scoped SPI so each service URL gets its own instance.
 *
 * <p>Created by shenhongxi on 2021/3/6.
 */
@Spi(scope = Scope.PROTOTYPE)
public interface Provider<T> extends Caller<T> {

    Method lookupMethod(String methodName, String paramDesc);

    T getImpl();

    /**
     * Async invocation that returns a CompletableFuture.
     * Default implementation wraps the synchronous call() result.
     */
    default CompletableFuture<Response> callAsync(Request request) {
        return CompletableFuture.completedFuture(call(request));
    }
}