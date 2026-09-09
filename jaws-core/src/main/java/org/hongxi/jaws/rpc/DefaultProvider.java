package org.hongxi.jaws.rpc;

import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.common.extension.Extension;
import org.hongxi.jaws.common.util.ExceptionUtils;
import org.hongxi.jaws.exception.JawsBizException;
import org.hongxi.jaws.exception.JawsErrorCode;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.transport.StreamSubject;
import org.hongxi.jaws.stream.StreamSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
 * Also supports streaming methods that return {@link StreamSource}
 * via {@code callStream()}.
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

            response.setThrowable(exception);
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
                            asyncResponse.setThrowable(new JawsServiceException(
                                    "provider async call timeout: " + request.getInterfaceName() + "." + request.getMethodName(),
                                    JawsErrorCode.SERVICE_TIMEOUT));
                        } else if (cause instanceof Exception ex) {
                            asyncResponse.setThrowable(new JawsBizException("provider async call failed",
                                    ExceptionUtils.toSerializableException(ex, method, interfaceClass)));
                        } else {
                            asyncResponse.setThrowable(new JawsServiceException("provider async call failed with fatal error: " + cause));
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
            response.setThrowable(new JawsBizException("provider call failed",
                    ExceptionUtils.toSerializableException(bizCause, method, interfaceClass)));

            // not print stack in error log when exception declared in method
            boolean logException = true;
            for (Class<?> clazz : method.getExceptionTypes()) {
                if (clazz.isInstance(response.getThrowable().getCause())) {
                    logException = false;
                    defaultTransferExceptionStack = false;
                    break;
                }
            }
            if (logException) {
                log.error("Exception caught during method invocation. request: {}", request, e);
            } else {
                log.info("Exception caught during method invocation. request: {}, exception: {}",
                        request, response.getThrowable().getCause().toString());
            }
        } catch (Throwable t) {
            // If provider encounters an Error, stringify it into the message instead of
            // attaching it as cause: Error classes are usually absent in the consumer's
            // class loader and would break response deserialization.
            Throwable fatalCause = t.getCause() != null ? t.getCause() : t;
            response.setThrowable(new JawsServiceException(
                    "provider has encountered a fatal error: " + ExceptionUtils.toString(fatalCause)));
            // Also log for Throwable
            log.error("Exception caught during method invocation. request: {}", request, t);
        }

        if (response.getThrowable() != null) {
            // Whether to transfer exception stack trace
            boolean transferExceptionStack = this.url.getParameter(UrlParam.Transport.TRANSFER_EXCEPTION_STACK.getName(), defaultTransferExceptionStack);
            if (!transferExceptionStack) {
                ExceptionUtils.setMockStackTrace(response.getThrowable().getCause());
            }
        }
        response.setAttachments(request.getAttachments());
        return CompletableFuture.completedFuture(response);
    }

    @Override
    public StreamSource<Object> callStream(Request request, StreamSource<Object> requestStream) {
        Method method = lookupMethod(request.getMethodName(), request.getParamDesc());

        if (method == null) {
            throw new JawsServiceException("Service method not found: " + request.getInterfaceName() + "."
                    + request.getMethodName() + "(" + request.getParamDesc() + ")",
                    JawsErrorCode.SERVICE_METHOD_NOT_FOUND);
        }

        try {
            // Server-streaming: requestStream is null, method returns StreamSource
            if (requestStream == null) {
                Object result = method.invoke(ref, request.getArguments());
                if (result instanceof StreamSource<?> source) {
                    //noinspection unchecked
                    return (StreamSource<Object>) source;
                }
                throw new JawsBizException("server-streaming method must return StreamSource: "
                        + request.getInterfaceName() + "." + request.getMethodName());
            }

            // Client/bidi-streaming: requestStream is the first argument
            Object[] args = request.getArguments();
            Object[] streamArgs;
            if (args != null && args.length > 0) {
                streamArgs = new Object[args.length + 1];
                streamArgs[0] = requestStream;
                System.arraycopy(args, 0, streamArgs, 1, args.length);
            } else {
                streamArgs = new Object[]{requestStream};
            }
            Object result = method.invoke(ref, streamArgs);

            // Bidi-streaming: method returns StreamSource
            if (result instanceof StreamSource<?> source) {
                //noinspection unchecked
                return (StreamSource<Object>) source;
            }

            // Client-streaming: method returns a single value (or CompletableFuture);
            // wrap it in a StreamSource for the transport layer.
            StreamSubject<Object> observer = new StreamSubject<>();
            if (result instanceof CompletableFuture<?> future) {
                future.whenComplete((value, throwable) -> {
                    if (throwable != null) {
                        observer.onError(throwable);
                    } else {
                        if (value != null) {
                            observer.onNext(value);
                        }
                        observer.onCompleted();
                    }
                });
            } else {
                if (result != null) {
                    observer.onNext(result);
                }
                observer.onCompleted();
            }
            return observer;
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new JawsBizException("provider stream call failed",
                    ExceptionUtils.toSerializableException(cause, method, interfaceClass));
        }
    }
}