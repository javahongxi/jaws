package org.hongxi.jaws.rpc;

import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.common.extension.Extension;
import org.hongxi.jaws.common.util.ExceptionUtils;
import org.hongxi.jaws.exception.JawsBizException;
import org.hongxi.jaws.exception.JawsErrorCode;
import org.hongxi.jaws.exception.JawsServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Created by shenhongxi on 2021/3/7.
 */
@Extension("jaws")
public class DefaultProvider<T> extends AbstractProvider<T> {

    private static final Logger log = LoggerFactory.getLogger(DefaultProvider.class);

    protected T ref;

    public DefaultProvider(Class<T> interfaceClass, URL url, T ref) {
        super(interfaceClass, url);
        this.ref = ref;
    }

    @Override
    public T getImpl() {
        return ref;
    }

    @Override
    public CompletableFuture<Response> invoke(Request request) {
        DefaultResponse response = new DefaultResponse();

        Method method = lookupMethod(request.getMethodName(), request.getParamDesc());

        if (method == null) {
            JawsServiceException exception =
                    new JawsServiceException("Service method not exist: " + request.getInterfaceName() + "." + request.getMethodName()
                            + "(" + request.getParamDesc() + ")", JawsErrorCode.SERVICE_METHOD_NOT_FOUND);

            response.setException(exception);
            return CompletableFuture.completedFuture(response);
        }

        boolean defaultTransExceptionStack = UrlParam.Transport.TRANS_EXCEPTION_STACK.boolValue();
        try {
            Object value = method.invoke(ref, request.getArguments());
            if (value instanceof CompletableFuture<?> future) {
                long timeout = this.url.getMethodParameter(
                        request.getMethodName(), request.getParamDesc(),
                        UrlParam.Transport.REQUEST_TIMEOUT.getName(), UrlParam.Transport.REQUEST_TIMEOUT.intValue());
                if (timeout > 0) {
                    future = future.orTimeout(timeout, TimeUnit.MILLISECONDS);
                }
                return future.handle((result, throwable) -> {
                    DefaultResponse asyncResponse = new DefaultResponse();
                    asyncResponse.setAttachments(request.getAttachments());
                    if (throwable != null) {
                        Throwable cause = throwable instanceof CompletionException ? throwable.getCause() : throwable;
                        if (cause instanceof TimeoutException) {
                            asyncResponse.setException(new JawsServiceException(
                                    "provider async call timeout: " + request.getInterfaceName() + "." + request.getMethodName(),
                                    JawsErrorCode.SERVICE_TIMEOUT));
                        } else if (cause instanceof Exception ex) {
                            asyncResponse.setException(new JawsBizException("provider async call process error", ex));
                        } else {
                            asyncResponse.setException(new JawsServiceException("provider async call fatal error: " + cause));
                        }
                    } else {
                        asyncResponse.setValue(result);
                    }
                    return asyncResponse;
                });
            }
            response.setValue(value);
        } catch (Exception e) {
            if (e.getCause() != null) {
                response.setException(new JawsBizException("provider call process error", e.getCause()));
            } else {
                response.setException(new JawsBizException("provider call process error", e));
            }

            // not print stack in error log when exception declared in method
            boolean logException = true;
            for (Class<?> clazz : method.getExceptionTypes()) {
                if (clazz.isInstance(response.getException().getCause())) {
                    logException = false;
                    defaultTransExceptionStack = false;
                    break;
                }
            }
            if (logException) {
                log.error("Exception caught when during method invocation. request: {}", request, e);
            } else {
                log.info("Exception caught when during method invocation. request: {}, exception: {}",
                        request, response.getException().getCause().toString());
            }
        } catch (Throwable t) {
            // If provider encounters an Error, convert it to Exception to prevent dragging down the caller
            if (t.getCause() != null) {
                response.setException(new JawsServiceException("provider has encountered a fatal error!", t.getCause()));
            } else {
                response.setException(new JawsServiceException("provider has encountered a fatal error!", t));
            }
            // Also log for Throwable
            log.error("Exception caught when during method invocation. request:{}", request, t);
        }

        if (response.getException() != null) {
            // Whether to transmit business exception stack
            boolean transExceptionStack = this.url.getParameter(UrlParam.Transport.TRANS_EXCEPTION_STACK.getName(), defaultTransExceptionStack);
            // Do not transmit business exception stack
            if (!transExceptionStack) {
                ExceptionUtils.setMockStackTrace(response.getException().getCause());
            }
        }
        response.setAttachments(request.getAttachments());
        return CompletableFuture.completedFuture(response);
    }
}