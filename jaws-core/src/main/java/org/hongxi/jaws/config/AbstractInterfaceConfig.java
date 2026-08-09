package org.hongxi.jaws.config;

import org.apache.commons.lang3.StringUtils;
import java.io.Serial;
import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.common.util.NetUtils;
import org.hongxi.jaws.common.util.ReflectUtils;
import org.hongxi.jaws.common.util.URLUtils;
import org.hongxi.jaws.exception.JawsErrorMsgConstants;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.registry.RegistryService;
import org.hongxi.jaws.rpc.URL;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.*;
import java.util.stream.Collectors;

/**
 * <pre>
 * Interface config，
 *
 * 配置约定
 * 	  1 service 和 reference 端相同的参数的含义一定相同；
 *    2 service端参数的覆盖策略：protocol -> service，前面的配置会被后面的config参数覆盖；
 *    3 registry 参数不进入service、reference端的参数列表；
 *    4 reference端以注册中心返回的server URL为基础，用reference参数覆盖（application、module保留server端的值）
 * </pre>
 * <p>
 * Created by shenhongxi on 2021/3/5.
 */

public class AbstractInterfaceConfig extends AbstractConfig {

    @Serial
    private static final long serialVersionUID = 4841644071068578653L;

    // 暴露、使用的协议，暴露可以使用多种协议，但client只能用一种协议进行访问，原因是便于client的管理
    protected List<ProtocolConfig> protocols;

    // 注册中心的配置列表
    protected List<RegistryConfig> registries;

    // 解析后的所有注册中心url
    protected List<URL> registryUrls = new ArrayList<>();

    // 应用名称
    protected String application;

    // 模块名称
    protected String module;

    // 分组
    protected String group;

    // 服务版本
    protected String version;

    // 代理类型
    protected String proxy;

    // 过滤器
    protected String filter;

    // 是否共享 channel
    protected Boolean shareChannel;

    // if throw exception when call failure，the default value is true
    protected Boolean throwException;

    // 请求超时时间
    protected Integer requestTimeout;

    // 是否记录访问日志，true记录，false不记录
    protected String accessLog;

    // 是否进行check，如果为true，则在监测失败后抛异常
    protected String check;

    // 重试次数
    protected Integer retries;

    protected String codec;

    // 是否需要传输rpc server 端业务异常栈。默认true
    protected Boolean transExceptionStack;

    // 具体到方法的配置
    protected List<MethodConfig> methods;

    /*
     * 解析注册中心URL
     */
    protected void loadRegistryUrls() {
        registryUrls.clear();
        if (registries != null && !registries.isEmpty()) {
            for (RegistryConfig config : registries) {
                String address = config.getAddress();
                if (StringUtils.isBlank(address)) {
                    address = NetUtils.LOCALHOST + ":" + JawsConstants.DEFAULT_INT_VALUE;
                }
                Map<String, String> map = new HashMap<>();
                config.appendConfigParams(map);

                map.put(URLParamType.application.getName(), getApplication());
                map.put(URLParamType.path.getName(), RegistryService.class.getName());
                map.put(URLParamType.refreshTimestamp.getName(), String.valueOf(System.currentTimeMillis()));

                // 设置默认的registry protocol，parse完protocol后，需要去掉该参数
                if (!map.containsKey(URLParamType.protocol.getName())) {
                    if (address.contains("://")) {
                        map.put(URLParamType.protocol.getName(), address.substring(0, address.indexOf("://")));
                    } else {
                        map.put(URLParamType.protocol.getName(), JawsConstants.REGISTRY_PROTOCOL_LOCAL);
                    }
                }
                // address内部可能包含多个注册中心地址
                List<URL> urls = URLUtils.parseURLs(address, map);
                if (urls != null && !urls.isEmpty()) {
                    for (URL url : urls) {
                        url.removeParameter(URLParamType.protocol.getName());
                        registryUrls.add(url);
                    }
                }
            }
        }
    }

    protected void checkInterfaceAndMethods(Class<?> interfaceClass, List<MethodConfig> methods) {
        if (interfaceClass == null) {
            throw new IllegalStateException("interface not allow null!");
        }
        if (!interfaceClass.isInterface()) {
            throw new IllegalStateException("The interface class " + interfaceClass + " is not a interface!");
        }
        if (methods == null || methods.isEmpty()) {
            return;
        }
        // 检查方法是否在接口中存在
        for (MethodConfig methodBean : methods) {
            String methodName = methodBean.getName();
            if (methodName == null || methodName.isEmpty()) {
                throw new IllegalStateException("MethodConfig name is required! Please check the method config for interface \""
                        + interfaceClass.getName() + "\".");
            }
            Method matchedMethod = null;
            for (Method method : interfaceClass.getMethods()) {
                if (method.getName().equals(methodName)) {
                    if (methodBean.getArgumentTypes() != null
                            && ReflectUtils.getMethodParamDesc(method).equals(methodBean.getArgumentTypes())) {
                        matchedMethod = method;
                        break;
                    }
                    if (methodBean.getArgumentTypes() != null) {
                        continue;
                    }
                    if (matchedMethod != null) {
                        throw new JawsFrameworkException("The interface " + interfaceClass.getName() + " has more than one method "
                                + methodName + " , must set argumentTypes attribute.", JawsErrorMsgConstants.FRAMEWORK_INIT_ERROR);
                    }
                    matchedMethod = method;
                }
            }
            if (matchedMethod == null) {
                throw new JawsFrameworkException("The interface " + interfaceClass.getName() + " not found method " + methodName,
                        JawsErrorMsgConstants.FRAMEWORK_INIT_ERROR);
            }
            methodBean.setArgumentTypes(ReflectUtils.getMethodParamDesc(matchedMethod));
        }
    }

    protected String getLocalHostAddress() {
        Map<String, Integer> regHostPorts = registryUrls.stream()
                .filter(ru -> StringUtils.isNotBlank(ru.getHost()) && ru.getPort() > 0)
                .collect(Collectors.toMap(URL::getHost, URL::getPort, (a, b) -> b));

        InetAddress address = NetUtils.getLocalAddress(regHostPorts);
        String localAddress = address != null ? address.getHostAddress() : null;

        if (NetUtils.isValidLocalHost(localAddress)) {
            return localAddress;
        }
        throw new JawsServiceException("Please config local server hostname with intranet IP first!",
                JawsErrorMsgConstants.FRAMEWORK_INIT_ERROR);
    }

    public List<ProtocolConfig> getProtocols() {
        return protocols;
    }

    public void setProtocols(List<ProtocolConfig> protocols) {
        this.protocols = protocols;
    }

    public void setProtocol(ProtocolConfig protocol) {
        this.protocols = Collections.singletonList(protocol);
    }

    public List<RegistryConfig> getRegistries() {
        return registries;
    }

    public void setRegistries(List<RegistryConfig> registries) {
        this.registries = registries;
    }

    public void setRegistry(RegistryConfig registry) {
        this.registries = Collections.singletonList(registry);
    }

    public String getApplication() {
        return application;
    }

    public void setApplication(String application) {
        this.application = application;
    }

    public String getModule() {
        return module;
    }

    public void setModule(String module) {
        this.module = module;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getProxy() {
        return proxy;
    }

    public void setProxy(String proxy) {
        this.proxy = proxy;
    }

    public String getFilter() {
        return filter;
    }

    public void setFilter(String filter) {
        this.filter = filter;
    }

    public Boolean getShareChannel() {
        return shareChannel;
    }

    public void setShareChannel(Boolean shareChannel) {
        this.shareChannel = shareChannel;
    }

    public Boolean getThrowException() {
        return throwException;
    }

    public void setThrowException(Boolean throwException) {
        this.throwException = throwException;
    }

    public Integer getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Integer requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public String getAccessLog() {
        return accessLog;
    }

    public void setAccessLog(String accessLog) {
        this.accessLog = accessLog;
    }

    public String getCheck() {
        return check;
    }

    public void setCheck(String check) {
        this.check = check;
    }

    public Integer getRetries() {
        return retries;
    }

    public void setRetries(Integer retries) {
        this.retries = retries;
    }

    public String getCodec() {
        return codec;
    }

    public void setCodec(String codec) {
        this.codec = codec;
    }

    public Boolean getTransExceptionStack() {
        return transExceptionStack;
    }

    public void setTransExceptionStack(Boolean transExceptionStack) {
        this.transExceptionStack = transExceptionStack;
    }

    public List<MethodConfig> getMethods() {
        return methods;
    }

    public void setMethods(List<MethodConfig> methods) {
        this.methods = methods;
    }
}