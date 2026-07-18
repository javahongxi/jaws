package org.hongxi.jaws.transport;

import org.apache.commons.lang3.StringUtils;
import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.common.extension.ExtensionLoader;
import org.hongxi.jaws.common.util.GenericUtils;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * service 消息处理
 * <p>
 * <pre>
 * 		1） 多个service的支持
 * 		2） 区分service的方式： group/interface/version
 * </pre>
 * <p>
 * Created by shenhongxi on 2021/4/21.
 */
public class ProviderMessageRouter implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(ProviderMessageRouter.class);

    protected Map<String, Provider<?>> providers = new HashMap<>();

    // 所有暴露出去的方法计数
    // 比如：messageRouter 里面涉及2个Service: ServiceA 有5个public method，ServiceB
    // 有10个public method，那么就是15
    protected AtomicInteger methodCounter = new AtomicInteger(0);

    protected ProviderProtectedStrategy strategy;

    public ProviderMessageRouter() {
        strategy = ExtensionLoader.getExtensionLoader(ProviderProtectedStrategy.class).getExtension(URLParamType.providerProtectedStrategy.value());
        strategy.setMethodCounter(methodCounter);
    }

    public ProviderMessageRouter(URL url) {
        String providerProtectedStrategy = url.getParameter(URLParamType.providerProtectedStrategy.getName(), URLParamType.providerProtectedStrategy.value());
        strategy = ExtensionLoader.getExtensionLoader(ProviderProtectedStrategy.class).getExtension(providerProtectedStrategy);
        strategy.setMethodCounter(methodCounter);
    }

    public ProviderMessageRouter(Provider<?> provider) {
        addProvider(provider);
    }

    @Override
    public Object handle(Channel channel, Object message) {
        if (channel == null || message == null) {
            throw new JawsFrameworkException("RequestRouter handler(channel, message) params is null");
        }

        if (!(message instanceof Request)) {
            throw new JawsFrameworkException("RequestRouter message type not support: " + message.getClass());
        }

        Request request = (Request) message;

        String serviceKey = JawsFrameworkUtils.getServiceKey(request);

        Provider<?> provider = providers.get(serviceKey);

        if (provider == null) {
            log.error(this.getClass().getSimpleName() + " handler Error: provider not exist serviceKey=" + serviceKey + " "
                    + JawsFrameworkUtils.toString(request));
            JawsServiceException exception =
                    new JawsServiceException(this.getClass().getSimpleName() + " handler Error: provider not exist serviceKey="
                            + serviceKey + " " + JawsFrameworkUtils.toString(request));

            DefaultResponse response = JawsFrameworkUtils.buildErrorResponse(request, exception);
            return response;
        }

        // Handle generic invocation
        boolean isGeneric = "true".equals(request.getAttachments().get("$generic"));
        if (isGeneric) {
            return handleGenericInvocation(request, provider);
        }

        Method method = provider.lookupMethod(request.getMethodName(), request.getParametersDesc());
        fillParamDesc(request, method);
        Response response = call(request, provider);
        response.setSerializationNumber(request.getSerializationNumber());
        return response;
    }

    /**
     * Handle generic invocation: convert Map arguments to actual POJO types,
     * invoke the real method, and convert the result back to Map/simple types.
     */
    private Response handleGenericInvocation(Request request, Provider<?> provider) {
        String methodName = request.getMethodName();
        String parametersDesc = request.getParametersDesc();
        Object[] originalArgs = request.getArguments();

        Method method = provider.lookupMethod(methodName, parametersDesc);
        if (method == null) {
            JawsServiceException exception = new JawsServiceException(
                    "Generic invocation: method not found: " + request.getInterfaceName() + "." + methodName
                            + "(" + parametersDesc + ")");
            DefaultResponse response = JawsFrameworkUtils.buildErrorResponse(request, exception);
            response.setSerializationNumber(request.getSerializationNumber());
            return response;
        }

        // Convert arguments from Map to actual POJO types
        Class<?>[] paramTypes = method.getParameterTypes();
        Object[] convertedArgs = new Object[paramTypes.length];
        if (originalArgs != null) {
            for (int i = 0; i < paramTypes.length && i < originalArgs.length; i++) {
                convertedArgs[i] = GenericUtils.convertArgument(originalArgs[i], paramTypes[i]);
            }
        }

        // Update the request with converted arguments and real parameter description
        if (request instanceof DefaultRequest dr) {
            dr.setArguments(convertedArgs);
            dr.setParametersDesc(org.hongxi.jaws.common.util.ReflectUtils.getMethodParamDesc(method));
        }

        fillParamDesc(request, method);
        Response response = call(request, provider);

        // Convert the result for generic response
        if (response.getException() == null && response.getValue() != null) {
            Object convertedResult = GenericUtils.convertResult(response.getValue());
            if (response instanceof DefaultResponse dr) {
                dr.setValue(convertedResult);
            }
        }

        response.setSerializationNumber(request.getSerializationNumber());
        return response;
    }

    protected Response call(Request request, Provider<?> provider) {
        try {
            return strategy.call(request, provider);
        } catch (Exception e) {
            return JawsFrameworkUtils.buildErrorResponse(request, new JawsBizException("provider call process error", e));
        }
    }

    private void fillParamDesc(Request request, Method method) {
        if (method != null && StringUtils.isBlank(request.getParametersDesc())
                && request instanceof DefaultRequest dr) {
            dr.setParametersDesc(ReflectUtils.getMethodParamDesc(method));
            dr.setMethodName(method.getName());
        }
    }

    public synchronized void addProvider(Provider<?> provider) {
        String serviceKey = JawsFrameworkUtils.getServiceKey(provider.getUrl());
        if (providers.containsKey(serviceKey)) {
            throw new JawsFrameworkException("provider alread exist: " + serviceKey);
        }

        providers.put(serviceKey, provider);

        // 获取该service暴露的方法数：
        List<Method> methods = ReflectUtils.getPublicMethod(provider.getInterface());

        int publicMethodCount = methods.size();
        methodCounter.addAndGet(publicMethodCount);

        log.info("RequestRouter addProvider: url={} all_public_method_count={}", provider.getUrl(), methodCounter.get());
    }

    public synchronized void removeProvider(Provider<?> provider) {
        String serviceKey = JawsFrameworkUtils.getServiceKey(provider.getUrl());

        providers.remove(serviceKey);
        List<Method> methods = ReflectUtils.getPublicMethod(provider.getInterface());
        int publicMethodCount = methods.size();
        methodCounter.getAndSet(methodCounter.get() - publicMethodCount);

        log.info("RequestRouter removeProvider: url={} all_public_method_count={}", provider.getUrl(), methodCounter.get());
    }

    public int getPublicMethodCount() {
        return methodCounter.get();
    }

}