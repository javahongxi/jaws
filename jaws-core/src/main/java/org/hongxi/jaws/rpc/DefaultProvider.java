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

/**
 * Created by shenhongxi on 2021/3/7.
 */
@SpiMeta(name = "jaws")
public class DefaultProvider<T> extends AbstractProvider<T> {

    private static final Logger logger = LoggerFactory.getLogger(DefaultProvider.class);

    protected T proxyImpl;

    public DefaultProvider(T proxyImpl, URL url, Class<T> clz) {
        super(url, clz);
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
                logger.error("Exception caught when during method invocation. request: {}", request.toString(), e);
            } else {
                logger.info("Exception caught when during method invocation. request: {}, exception: {}",
                        request.toString(), response.getException().getCause().toString());
            }
        } catch (Throwable t) {
            // 如果服务发生Error，将Error转化为Exception，防止拖垮调用方
            if (t.getCause() != null) {
                response.setException(new JawsServiceException("provider has encountered a fatal error!", t.getCause()));
            } else {
                response.setException(new JawsServiceException("provider has encountered a fatal error!", t));
            }
            //对于Throwable,也记录日志
            logger.error("Exception caught when during method invocation. request:" + request.toString(), t);
        }

        if (response.getException() != null) {
            //是否传输业务异常栈
            boolean transExceptionStack = this.url.getBooleanParameter(URLParamType.transExceptionStack.getName(), defaultThrowExceptionStack);
            if (!transExceptionStack) {//不传输业务异常栈
                ExceptionUtils.setMockStackTrace(response.getException().getCause());
            }
        }
        response.setAttachments(request.getAttachments());
        return response;
    }
}