package org.hongxi.jaws.transport;

import org.apache.commons.lang3.StringUtils;
import org.hongxi.jaws.common.util.RpcUtils;
import org.hongxi.jaws.common.util.ReflectUtils;
import org.hongxi.jaws.exception.JawsBizException;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.DefaultResponse;
import org.hongxi.jaws.rpc.Provider;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Flow;

/**
 * Abstract base class for provider-side message handling.
 * <p>
 * Handles common logic: provider lookup, method resolution, error handling,
 * and provider lifecycle management. Subclasses implement the specific
 * invocation strategy (e.g., normal vs generic invocation).
 */
public abstract class AbstractRequestHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(AbstractRequestHandler.class);

    // ConcurrentHashMap: requests read providers lock-free on every RPC
    // while export/unexport mutate it concurrently
    protected final ConcurrentMap<String, Provider<?>> providers = new ConcurrentHashMap<>();

    @Override
    public CompletableFuture<Object> handleAsync(Channel channel, Object message) {
        if (channel == null || message == null) {
            throw new JawsFrameworkException("handler(channel, message): channel and message must not be null");
        }
        if (!(message instanceof Request request)) {
            throw new JawsFrameworkException("unsupported message type: " + message.getClass());
        }

        String serviceKey = RpcUtils.getServiceKey(request);
        Provider<?> provider = providers.get(serviceKey);

        if (provider == null) {
            log.error("{} no provider found for serviceKey={} {}",
                    this.getClass().getSimpleName(), serviceKey, RpcUtils.toString(request));
            JawsServiceException exception = new JawsServiceException(
                    this.getClass().getSimpleName() + " no provider found for serviceKey="
                            + serviceKey + " " + RpcUtils.toString(request));
            DefaultResponse response = RpcUtils.buildErrorResponse(request, exception);
            return CompletableFuture.completedFuture(response);
        }

        Method method = provider.lookupMethod(request.getMethodName(), request.getParamDesc());
        fillParamDesc(request, method);
        return doHandleAsync(request, provider, method);
    }

    /**
     * Subclass-specific invocation logic.
     *
     * @param request  the incoming RPC request
     * @param provider the resolved provider
     * @param method   the resolved method (maybe null if not found)
     * @return a CompletableFuture representing the async processing result
     */
    protected abstract CompletableFuture<Object> doHandleAsync(Request request, Provider<?> provider, Method method);

    protected CompletableFuture<Response> callAsync(Request request, Provider<?> provider) {
        try {
            return provider.callAsync(request);
        } catch (Exception e) {
            return CompletableFuture.completedFuture(
                    RpcUtils.buildErrorResponse(request, new JawsBizException("provider call failed", e)));
        }
    }

    protected void fillParamDesc(Request request, Method method) {
        if (method != null && StringUtils.isBlank(request.getParamDesc())
                && request instanceof DefaultRequest dr) {
            dr.setParamDesc(ReflectUtils.getMethodParamDesc(method));
            dr.setMethodName(method.getName());
        }
    }

    public void addProvider(Provider<?> provider) {
        String serviceKey = RpcUtils.getServiceKey(provider.getUrl());
        if (providers.putIfAbsent(serviceKey, provider) != null) {
            throw new JawsFrameworkException("provider already exists: " + serviceKey);
        }
        log.info("{} addProvider: url={}", this.getClass().getSimpleName(), provider.getUrl());
    }

    public void removeProvider(Provider<?> provider) {
        String serviceKey = RpcUtils.getServiceKey(provider.getUrl());
        providers.remove(serviceKey);
        log.info("{} removeProvider: url={}", this.getClass().getSimpleName(), provider.getUrl());
    }

    @Override
    public Provider<?> findProviderByInterface(String interfaceName) {
        for (Provider<?> provider : providers.values()) {
            if (interfaceName.equals(provider.getInterface().getName())) {
                return provider;
            }
        }
        return null;
    }

    /**
     * Handle a server-streaming request: look up the provider, resolve the
     * method, and delegate to {@link Provider#callStream(Request)}.
     *
     * @param channel the transport channel
     * @param message the incoming RPC request
     * @return a {@link Flow.Publisher} emitting the stream items
     */
    public Flow.Publisher<Object> handleStream(Channel channel, Object message) {
        if (channel == null || message == null) {
            throw new JawsFrameworkException("handler(channel, message): channel and message must not be null");
        }
        if (!(message instanceof Request request)) {
            throw new JawsFrameworkException("unsupported message type: " + message.getClass());
        }

        String serviceKey = RpcUtils.getServiceKey(request);
        Provider<?> provider = providers.get(serviceKey);

        if (provider == null) {
            log.error("{} no provider found for serviceKey={} {}",
                    this.getClass().getSimpleName(), serviceKey, RpcUtils.toString(request));
            throw new JawsServiceException(
                    this.getClass().getSimpleName() + " no provider found for serviceKey="
                            + serviceKey + " " + RpcUtils.toString(request));
        }

        Method method = provider.lookupMethod(request.getMethodName(), request.getParamDesc());
        fillParamDesc(request, method);
        return provider.callStream(request);
    }
}
