package org.hongxi.jaws.config;

import org.apache.commons.lang3.StringUtils;
import java.io.Serial;
import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.common.util.NetUtils;
import org.hongxi.jaws.common.util.ReflectUtils;
import org.hongxi.jaws.common.util.UrlUtils;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.registry.RegistryService;
import org.hongxi.jaws.rpc.URL;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.*;

/**
 * <pre>
 * Interface config.
 *
 * Configuration conventions:
 *   1. The meaning of the same parameter on service and reference sides must be identical;
 *   2. Service-side parameter override strategy: protocol -> service, earlier config is overridden by later config;
 *   3. Registry parameters do not enter the parameter list of service/reference;
 *   4. The reference side uses the server URL returned by the registry as the base,
 *      overriding with reference parameters (application and module retain server-side values).
 * </pre>
 * <p>
 * Created by shenhongxi on 2021/3/5.
 */
public class AbstractInterfaceConfig extends AbstractConfig {

    @Serial
    private static final long serialVersionUID = 4841644071068578653L;

    // ========== Server & Client shared configuration ==========

    /**
     * List of protocols for service exposure or reference.
     */
    protected List<ProtocolConfig> protocols;

    /**
     * Registries for service registration or discovery.
     */
    protected List<RegistryConfig> registries;

    /**
     * The application name.
     */
    protected String application;

    /**
     * The module name.
     */
    protected String module;

    /**
     * The service group.
     */
    protected String group;

    /**
     * The service version.
     */
    protected String version;

    /**
     * Filters for service exposure or reference (multiple filters can be separated by commas).
     */
    protected String filter;

    /**
     * Method-specific configuration.
     */
    protected List<MethodConfig> methods;

    /**
     * Parsed registry URLs
     */
    protected List<URL> registryUrls = new ArrayList<>();

    // ========== Server-only configuration ==========

    /**
     * Whether to log access records; true = log, false = do not log.
     */
    protected String accessLog;

    /**
     * Whether to transmit the RPC server-side business exception stack; default is true.
     */
    protected Boolean transExceptionStack;

    // ========== Client-only configuration ==========

    /**
     * Whether to throw exception on call failure; default is true.
     */
    protected Boolean throwException;

    /**
     * Request timeout in milliseconds.
     */
    protected Integer requestTimeout;

    /**
     * Whether to perform a startup check; if true, an exception is thrown when the check fails.
     */
    protected Boolean check;

    /**
     * Number of retries.
     */
    protected Integer retries;

    /**
     * Load balancing strategy.
     */
    protected String loadBalance;

    /**
     * High available strategy.
     */
    protected String haStrategy;

    @Override
    protected void collectParams(Map<String, String> params) {
        putIfPresent(params, "application", application);
        putIfPresent(params, "module", module);
        putIfPresent(params, "group", group);
        putIfPresent(params, "version", version);
        putIfPresent(params, "filter", filter);
        // server-only
        putIfPresent(params, "accessLog", accessLog);
        putIfPresent(params, "transExceptionStack", transExceptionStack);
        // client-only
        putIfPresent(params, "throwException", throwException);
        putIfPresent(params, "requestTimeout", requestTimeout);
        putIfPresent(params, "check", check);
        putIfPresent(params, "retries", retries);
        putIfPresent(params, "loadBalance", loadBalance);
        putIfPresent(params, "haStrategy", haStrategy);
    }

    /**
     * Parse registry URLs.
     */
    protected void loadRegistryUrls() {
        registryUrls.clear();
        if (registries != null && !registries.isEmpty()) {
            for (RegistryConfig config : registries) {
                String address = config.getAddress();
                if (StringUtils.isBlank(address)) {
                    address = NetUtils.LOCALHOST + ":" + 0;
                }
                Map<String, String> map = new HashMap<>();
                config.appendConfigParams(map);

                map.put(UrlParam.Identity.APPLICATION.getName(), getApplication());
                map.put(UrlParam.Identity.PATH.getName(), RegistryService.class.getName());

                // Determine registry protocol: prefer parsing from address, then RegistryConfig.protocol, finally fall back to local
                String protocol;
                if (address.contains(JawsConstants.PROTOCOL_SEPARATOR)) {
                    protocol = address.substring(0, address.indexOf(JawsConstants.PROTOCOL_SEPARATOR));
                } else if (StringUtils.isNotBlank(config.getProtocol())) {
                    protocol = config.getProtocol();
                } else {
                    protocol = JawsConstants.REGISTRY_PROTOCOL_LOCAL;
                }
                map.put(UrlParam.Transport.PROTOCOL.getName(), protocol);
                // The address may contain multiple registry addresses
                List<URL> urls = UrlUtils.parseURLs(address, map);
                if (urls != null && !urls.isEmpty()) {
                    for (URL url : urls) {
                        // Protocol information is already encoded in the URL structure; no longer retained in parameters
                        url.removeParameter(UrlParam.Transport.PROTOCOL.getName());
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
        // Check whether each method exists in the interface
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
                                + methodName + " , must set argumentTypes attribute.");
                    }
                    matchedMethod = method;
                }
            }
            if (matchedMethod == null) {
                throw new JawsFrameworkException("The interface " + interfaceClass.getName() + " not found method " + methodName);
            }
            methodBean.setArgumentTypes(ReflectUtils.getMethodParamDesc(matchedMethod));
        }
    }

    protected String getLocalHostAddress() {
        Map<String, Integer> regHostPorts = new HashMap<>();
        for (URL ru : registryUrls) {
            for (URL backupUrl : ru.getBackupUrls()) {
                if (StringUtils.isNotBlank(backupUrl.getHost()) && backupUrl.getPort() > 0) {
                    regHostPorts.put(backupUrl.getHost(), backupUrl.getPort());
                }
            }
        }

        InetAddress address = NetUtils.getLocalAddress(regHostPorts);
        String localAddress = address != null ? address.getHostAddress() : null;

        if (NetUtils.isValidLocalHost(localAddress)) {
            return localAddress;
        }
        throw new JawsServiceException("Please config local server hostname with intranet IP first!");
    }

    // ========== Server & Client shared configuration getter/setter ==========

    public List<ProtocolConfig> getProtocols() {
        return protocols;
    }

    public void setProtocols(List<ProtocolConfig> protocols) {
        this.protocols = protocols;
    }

    public void setProtocol(ProtocolConfig protocol) {
        this.protocols = List.of(protocol);
    }

    public List<RegistryConfig> getRegistries() {
        return registries;
    }

    public void setRegistries(List<RegistryConfig> registries) {
        this.registries = registries;
    }

    public void setRegistry(RegistryConfig registry) {
        this.registries = List.of(registry);
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

    public String getFilter() {
        return filter;
    }

    public void setFilter(String filter) {
        this.filter = filter;
    }

    public List<MethodConfig> getMethods() {
        return methods;
    }

    public void setMethods(List<MethodConfig> methods) {
        this.methods = methods;
    }

    // ========== Server-only configuration getter/setter ==========

    public String getAccessLog() {
        return accessLog;
    }

    public void setAccessLog(String accessLog) {
        this.accessLog = accessLog;
    }

    public Boolean getTransExceptionStack() {
        return transExceptionStack;
    }

    public void setTransExceptionStack(Boolean transExceptionStack) {
        this.transExceptionStack = transExceptionStack;
    }

    // ========== Client-only configuration getter/setter ==========

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

    public Boolean getCheck() {
        return check;
    }

    public void setCheck(Boolean check) {
        this.check = check;
    }

    public Integer getRetries() {
        return retries;
    }

    public void setRetries(Integer retries) {
        this.retries = retries;
    }

    public String getLoadBalance() {
        return loadBalance;
    }

    public void setLoadBalance(String loadBalance) {
        this.loadBalance = loadBalance;
    }

    public String getHaStrategy() {
        return haStrategy;
    }

    public void setHaStrategy(String haStrategy) {
        this.haStrategy = haStrategy;
    }
}