package org.hongxi.jaws.rpc;

import org.apache.commons.lang3.StringUtils;
import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.common.util.JawsFrameworkUtils;
import org.hongxi.jaws.exception.JawsServiceException;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Created by shenhongxi on 2020/6/14.
 */
public class URL {

    private String protocol;

    private String host;

    private int port;

    private String path;

    private Map<String, String> parameters;

    private volatile transient Map<String, Number> numbers;

    public URL(String protocol, String host, int port, String path) {
        this(protocol, host, port, path, new HashMap<>());
    }

    public URL(String protocol, String host, int port, String path, Map<String, String> parameters) {
        this.protocol = protocol;
        this.host = host;
        this.port = port;
        this.path = path;
        this.parameters = parameters;
    }

    public static URL valueOf(String url) {
        if (StringUtils.isBlank(url)) {
            throw new JawsServiceException("url is null");
        }
        String protocol = null;
        String host = null;
        int port = 0;
        String path = null;
        Map<String, String> parameters = new HashMap<String, String>();;
        int i = url.indexOf("?"); // seperator between body and parameters
        if (i >= 0) {
            String[] parts = url.substring(i + 1).split("\\&");

            for (String part : parts) {
                part = part.trim();
                if (part.length() > 0) {
                    int j = part.indexOf('=');
                    if (j >= 0) {
                        parameters.put(part.substring(0, j), part.substring(j + 1));
                    } else {
                        parameters.put(part, part);
                    }
                }
            }
            url = url.substring(0, i);
        }
        i = url.indexOf("://");
        if (i >= 0) {
            if (i == 0) throw new IllegalStateException("url missing protocol: \"" + url + "\"");
            protocol = url.substring(0, i);
            url = url.substring(i + 3);
        } else {
            i = url.indexOf(":/");
            if (i >= 0) {
                if (i == 0) throw new IllegalStateException("url missing protocol: \"" + url + "\"");
                protocol = url.substring(0, i);
                url = url.substring(i + 1);
            }
        }

        i = url.indexOf("/");
        if (i >= 0) {
            path = url.substring(i + 1);
            url = url.substring(0, i);
        }

        i = url.indexOf(":");
        if (i >= 0 && i < url.length() - 1) {
            port = Integer.parseInt(url.substring(i + 1));
            url = url.substring(0, i);
        }
        if (url.length() > 0) host = url;
        return new URL(protocol, host, port, path, parameters);
    }

    public Map<String, String> getParameters() {
        return parameters;
    }

    public String getParameter(String name) {
        return parameters.get(name);
    }

    public String getParameter(String name, String defaultValue) {
        return parameters.getOrDefault(name, defaultValue);
    }

    public boolean getBooleanParameter(String name, boolean defaultValue) {
        String value = getParameter(name);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }

    public int getIntParameter(String name, int defaultValue) {
        String value = getParameter(name);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        return Integer.parseInt(value);
    }

    public void addParameters(Map<String, String> params) {
        parameters.putAll(params);
    }

    public void removeParameter(String name) {
        if (name != null) {
            parameters.remove(name);
        }
    }

    public String getServerPortStr() {
        return buildHostPortStr(host, port);
    }

    private String buildHostPortStr(String host, int defaultPort) {
        if (defaultPort <= 0) {
            return host;
        }

        int idx = host.indexOf(":");
        if (idx < 0) {
            return host + ":" + defaultPort;
        }

        int port = Integer.parseInt(host.substring(idx + 1));
        if (port <= 0) {
            return host.substring(0, idx + 1) + ":" + defaultPort;
        }

        return host;
    }

    public URL createCopy() {
        Map<String, String> params = new HashMap<String, String>();
        if (this.parameters != null) {
            params.putAll(this.parameters);
        }

        return new URL(protocol, host, port, path, params);
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = removeAsyncPath(path);
    }

    public String getVersion() {
        return getParameter(URLParamType.version.getName(), URLParamType.version.value());
    }

    public String getGroup() {
        return getParameter(URLParamType.group.getName(), URLParamType.group.value());
    }

    public Object getUri() {
        return protocol + JawsConstants.PROTOCOL_SEPARATOR + host + ":" + port
                + File.separator + path;
    }

    /**
     * 返回一个service or referer的identity,如果两个url的identity相同，则表示相同的一个service或者referer
     *
     * @return
     */
    public String getIdentity() {
        return protocol + JawsConstants.PROTOCOL_SEPARATOR + host + ":" + port +
                "/" + getParameter(URLParamType.group.getName(), URLParamType.group.value()) + "/" +
                getPath() + "/" + getParameter(URLParamType.version.getName(), URLParamType.version.value()) +
                "/" + getParameter(URLParamType.nodeType.getName(), URLParamType.nodeType.value());
    }

    public Integer getMethodParameter(String methodName, String paramDesc, String name, int defaultValue) {
        String key = methodName + "(" + paramDesc + ")." + name;
        Number n = getNumbers().get(key);
        if (n != null) {
            return n.intValue();
        }
        String value = getMethodParameter(methodName, paramDesc, name);
        if (value == null || value.length() == 0) {
            return defaultValue;
        }
        int i = Integer.parseInt(value);
        getNumbers().put(key, i);
        return i;
    }

    public String getMethodParameter(String methodName, String paramDesc, String name) {
        String value = getParameter(JawsConstants.METHOD_CONFIG_PREFIX + methodName + "(" + paramDesc + ")." + name);
        if (value == null || value.length() == 0) {
            return getParameter(name);
        }
        return value;
    }

    private Map<String, Number> getNumbers() {
        if (numbers == null) { // 允许并发重复创建
            numbers = new ConcurrentHashMap<String, Number>();
        }
        return numbers;
    }

    /**
     * because async call in client path with Async suffix,we need
     * remove Async suffix in path for subscribe.
     * @param path
     * @return
     */
    private String removeAsyncPath(String path){
        return JawsFrameworkUtils.removeAsyncSuffix(path);
    }

    @Override
    public int hashCode() {
        int factor = 31;
        int rs = 1;
        rs = factor * rs + Objects.hashCode(protocol);
        rs = factor * rs + Objects.hashCode(host);
        rs = factor * rs + Objects.hashCode(port);
        rs = factor * rs + Objects.hashCode(path);
        rs = factor * rs + Objects.hashCode(parameters);
        return rs;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof URL)) {
            return false;
        }
        URL ou = (URL) obj;
        if (!Objects.equals(this.protocol, ou.protocol)) {
            return false;
        }
        if (!Objects.equals(this.host, ou.host)) {
            return false;
        }
        if (!Objects.equals(this.port, ou.port)) {
            return false;
        }
        if (!Objects.equals(this.path, ou.path)) {
            return false;
        }
        return Objects.equals(this.parameters, ou.parameters);
    }

    @Override
    public String toString() {
        return toSimpleString();
    }

    // 包含协议、host、port、path、group
    public String toSimpleString() {
        return getUri() + "?group=" + getGroup();
    }
}
