package org.hongxi.jaws.transport;

import org.apache.commons.lang3.StringUtils;
import org.hongxi.jaws.common.util.JawsFrameworkUtils;
import org.hongxi.jaws.common.util.ReflectUtils;
import org.hongxi.jaws.exception.JawsBizException;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.rpc.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Abstract base class for provider-side message handling.
 * <p>
 * Handles common logic: provider lookup, method resolution, error handling,
 * and provider lifecycle management. Subclasses implement the specific
 * invocation strategy (e.g., normal vs generic invocation).
 */
public abstract class AbstractRequestHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(AbstractRequestHandler.class);

    protected final Map<String, Provider<?>> providers = new HashMap<>();

    @Override
    public CompletableFuture<Object> handleAsync(Channel channel, Object message) {
        if (channel == null || message == null) {
            throw new JawsFrameworkException("handler(channel, message) params is null");
        }
        if (!(message instanceof Request request)) {
            throw new JawsFrameworkException("message type not support: " + message.getClass());
        }

        String serviceKey = JawsFrameworkUtils.getServiceKey(request);
        Provider<?> provider = providers.get(serviceKey);

        if (provider == null) {
            log.error("{} handler Error: provider not exist serviceKey={} {}",
                    this.getClass().getSimpleName(), serviceKey, JawsFrameworkUtils.toString(request));
            JawsServiceException exception = new JawsServiceException(
                    this.getClass().getSimpleName() + " handler Error: provider not exist serviceKey="
                            + serviceKey + " " + JawsFrameworkUtils.toString(request));
            DefaultResponse response = JawsFrameworkUtils.buildErrorResponse(request, exception);
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
                    JawsFrameworkUtils.buildErrorResponse(request, new JawsBizException("provider call process error", e)));
        }
    }

    protected void fillParamDesc(Request request, Method method) {
        if (method != null && StringUtils.isBlank(request.getParamDesc())
                && request instanceof DefaultRequest dr) {
            dr.setParamDesc(ReflectUtils.getMethodParamDesc(method));
            dr.setMethodName(method.getName());
        }
    }

    public synchronized void addProvider(Provider<?> provider) {
        String serviceKey = JawsFrameworkUtils.getServiceKey(provider.getUrl());
        if (providers.containsKey(serviceKey)) {
            throw new JawsFrameworkException("provider already exists: " + serviceKey);
        }
        providers.put(serviceKey, provider);
        log.info("{} addProvider: url={}", this.getClass().getSimpleName(), provider.getUrl());
    }

    public synchronized void removeProvider(Provider<?> provider) {
        String serviceKey = JawsFrameworkUtils.getServiceKey(provider.getUrl());
        providers.remove(serviceKey);
        log.info("{} removeProvider: url={}", this.getClass().getSimpleName(), provider.getUrl());
    }
}
