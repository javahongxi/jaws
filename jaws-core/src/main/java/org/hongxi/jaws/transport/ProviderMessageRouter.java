package org.hongxi.jaws.transport;

import org.apache.commons.lang3.StringUtils;
import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.common.extension.ExtensionLoader;
import org.hongxi.jaws.common.util.JawsFrameworkUtils;
import org.hongxi.jaws.common.util.ReflectUtils;
import org.hongxi.jaws.exception.JawsBizException;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.rpc.*;
import org.hongxi.jaws.serialize.DeserializableObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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
 *
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
        Method method = provider.lookupMethod(request.getMethodName(), request.getParametersDesc());
        fillParamDesc(request, method);
        processLazyDeserialize(request, method);
        Response response = call(request, provider);
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

    private void processLazyDeserialize(Request request, Method method) {
        if (method != null && request.getArguments() != null && request.getArguments().length == 1
                && request.getArguments()[0] instanceof DeserializableObject
                && request instanceof DefaultRequest) {
            try {
                Object[] args = ((DeserializableObject) request.getArguments()[0]).deserializeMulti(method.getParameterTypes());
                ((DefaultRequest) request).setArguments(args);
            } catch (IOException e) {
                throw new JawsFrameworkException("deserialize parameters fail: " + request.toString() + ", error:" + e.getMessage());
            }
        }
    }

    private void fillParamDesc(Request request, Method method) {
        if (method != null && StringUtils.isBlank(request.getParametersDesc())
                && request instanceof DefaultRequest) {
            DefaultRequest dr = (DefaultRequest) request;
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