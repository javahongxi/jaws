package org.hongxi.jaws.rpc;

import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.common.extension.SpiMeta;
import org.hongxi.jaws.common.util.ExceptionUtils;
import org.hongxi.jaws.exception.JawsBizException;
import org.hongxi.jaws.exception.JawsErrorMsgConstants;
import org.hongxi.jaws.exception.JawsServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

/**
 * Created by shenhongxi on 2021/3/7.
 */
@SpiMeta(name = "jaws")
public class DefaultProvider<T> extends AbstractProvider<T> {

    private static final Logger log = LoggerFactory.getLogger(DefaultProvider.class);

    protected T proxyImpl;

    public DefaultProvider(T proxyImpl, URL url, Class<T> clazz) {
        super(url, clazz);
        this.proxyImpl = proxyImpl;
    }

    @Override
    public T getImpl() {
        return proxyImpl;
    }

    @Override
    public Response invoke(Request request) {
        DefaultResponse response = new DefaultResponse();

        Method method = lookupMethod(request.getMethodName(), request.getParametersDesc());

        if (method == null) {
            JawsServiceException exception =
                    new JawsServiceException("Service method not exist: " + request.getInterfaceName() + "." + request.getMethodName()
                            + "(" + request.getParametersDesc() + ")", JawsErrorMsgConstants.SERVICE_NOT_FOUND);

            response.setException(exception);
            return response;
        }

        boolean defaultThrowExceptionStack = URLParamType.transExceptionStack.boolValue();
        try {
            Object value = method.invoke(proxyImpl, request.getArguments());
            // Provider端异步支持：如果方法返回 CompletableFuture，等待结果
            if (value instanceof CompletableFuture<?> cf) {
                try {
                    long timeout = this.url.getMethodParameter(
                            request.getMethodName(), request.getParametersDesc(),
                            URLParamType.requestTimeout.getName(), URLParamType.requestTimeout.intValue());
                    if (timeout > 0) {
                        value = cf.get(timeout, java.util.concurrent.TimeUnit.MILLISECONDS);
                    } else {
                        value = cf.get();
                    }
                } catch (java.util.concurrent.ExecutionException ee) {
                    Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
                    if (cause instanceof Exception ex) {
                        response.setException(new JawsBizException("provider async call process error", ex));
                    } else {
                        response.setException(new JawsServiceException("provider async call fatal error: " + cause));
                    }
                    response.setAttachments(request.getAttachments());
                    return response;
                } catch (java.util.concurrent.TimeoutException te) {
                    response.setException(new JawsServiceException(
                            "provider async call timeout: " + request.getInterfaceName() + "." + request.getMethodName(),
                            JawsErrorMsgConstants.SERVICE_TIMEOUT));
                    response.setAttachments(request.getAttachments());
                    return response;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    response.setException(new JawsServiceException("provider async call interrupted",
                            JawsErrorMsgConstants.SERVICE_TIMEOUT));
                    response.setAttachments(request.getAttachments());
                    return response;
                }
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
                    defaultThrowExceptionStack = false;
                    break;
                }
            }
            if (logException) {
                log.error("Exception caught when during method invocation. request: {}", request.toString(), e);
            } else {
                log.info("Exception caught when during method invocation. request: {}, exception: {}",
                        request.toString(), response.getException().getCause().toString());
            }
        } catch (Throwable t) {
            // 如果服务发生Error，将Error转化为Exception，防止拖垮调用方
            if (t.getCause() != null) {
                response.setException(new JawsServiceException("provider has encountered a fatal error!", t.getCause()));
            } else {
                response.setException(new JawsServiceException("provider has encountered a fatal error!", t));
            }
            // 对于Throwable,也记录日志
            log.error("Exception caught when during method invocation. request:{}", request, t);
        }

        if (response.getException() != null) {
            // 是否传输业务异常栈
            boolean transExceptionStack = this.url.getBooleanParameter(URLParamType.transExceptionStack.getName(), defaultThrowExceptionStack);
            // 不传输业务异常栈
            if (!transExceptionStack) {
                ExceptionUtils.setMockStackTrace(response.getException().getCause());
            }
        }
        response.setAttachments(request.getAttachments());
        return response;
    }
}