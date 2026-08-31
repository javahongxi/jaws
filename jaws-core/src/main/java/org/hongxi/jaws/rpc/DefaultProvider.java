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
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Default {@link Provider} implementation ("jaws" extension) that dispatches requests
 * to the service implementation via reflection. Supports methods returning
 * {@link CompletableFuture} by chaining them into the response with an optional
 * per-method timeout, converts declared/business exceptions carefully (optionally
 * stripping stack traces before transferring), and turns {@link Error} throwables into
 * exceptions so a provider crash never takes down the caller.
 * <p>
 * Also supports server-streaming methods that return {@link Flow.Publisher}
 * via {@link #callStream(Request)}.
 *
 * <p>Created by shenhongxi on 2021/3/7.
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
                    new JawsServiceException("Service method not found: " + request.getInterfaceName() + "." + request.getMethodName()
                            + "(" + request.getParamDesc() + ")", JawsErrorCode.SERVICE_METHOD_NOT_FOUND);

            response.setException(exception);
            return CompletableFuture.completedFuture(response);
        }

        boolean defaultTransferExceptionStack = UrlParam.Transport.TRANSFER_EXCEPTION_STACK.boolValue();
        try {
            Object value = method.invoke(ref, request.getArguments());
            if (value instanceof CompletableFuture<?> future) {
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
                        Throwable cause = throwable instanceof CompletionException ? throwable.getCause() : throwable;
                        if (cause instanceof TimeoutException) {
                            asyncResponse.setException(new JawsServiceException(
                                    "provider async call timeout: " + request.getInterfaceName() + "." + request.getMethodName(),
                                    JawsErrorCode.SERVICE_TIMEOUT));
                        } else if (cause instanceof Exception ex) {
                            asyncResponse.setException(new JawsBizException("provider async call failed",
                                    ExceptionUtils.toSerializableException(ex, method, interfaceClass)));
                        } else {
                            asyncResponse.setException(new JawsServiceException("provider async call failed with fatal error: " + cause));
                        }
                    } else {
                        asyncResponse.setValue(result);
                    }
                    return asyncResponse;
                });
            }
            response.setValue(value);
        } catch (Exception e) {
            Throwable bizCause = e.getCause() != null ? e.getCause() : e;
            response.setException(new JawsBizException("provider call failed",
                    ExceptionUtils.toSerializableException(bizCause, method, interfaceClass)));

            // not print stack in error log when exception declared in method
            boolean logException = true;
            for (Class<?> clazz : method.getExceptionTypes()) {
                if (clazz.isInstance(response.getException().getCause())) {
                    logException = false;
                    defaultTransferExceptionStack = false;
                    break;
                }
            }
            if (logException) {
                log.error("Exception caught during method invocation. request: {}", request, e);
            } else {
                log.info("Exception caught during method invocation. request: {}, exception: {}",
                        request, response.getException().getCause().toString());
            }
        } catch (Throwable t) {
            // If provider encounters an Error, stringify it into the message instead of
            // attaching it as cause: Error classes are usually absent in the consumer's
            // class loader and would break response deserialization.
            Throwable fatalCause = t.getCause() != null ? t.getCause() : t;
            response.setException(new JawsServiceException(
                    "provider has encountered a fatal error: " + ExceptionUtils.toString(fatalCause)));
            // Also log for Throwable
            log.error("Exception caught during method invocation. request: {}", request, t);
        }

        if (response.getException() != null) {
            // Whether to transfer exception stack trace
            boolean transferExceptionStack = this.url.getParameter(UrlParam.Transport.TRANSFER_EXCEPTION_STACK.getName(), defaultTransferExceptionStack);
            if (!transferExceptionStack) {
                ExceptionUtils.setMockStackTrace(response.getException().getCause());
            }
        }
        response.setAttachments(request.getAttachments());
        return CompletableFuture.completedFuture(response);
    }

    @Override
    public Flow.Publisher<Object> callStream(Request request) {
        Method method = lookupMethod(request.getMethodName(), request.getParamDesc());

        if (method == null) {
            throw new JawsServiceException("Service method not found: " + request.getInterfaceName() + "."
                    + request.getMethodName() + "(" + request.getParamDesc() + ")",
                    JawsErrorCode.SERVICE_METHOD_NOT_FOUND);
        }

        try {
            Object result = method.invoke(ref, request.getArguments());
            if (result instanceof Flow.Publisher<?> publisher) {
                //noinspection unchecked
                return (Flow.Publisher<Object>) publisher;
            }
            throw new JawsBizException("server-streaming method must return Flow.Publisher: "
                    + request.getInterfaceName() + "." + request.getMethodName());
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new JawsBizException("provider stream call failed",
                    ExceptionUtils.toSerializableException(cause, method, interfaceClass));
        }
    }
}