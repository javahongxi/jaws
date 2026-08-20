package org.hongxi.jaws.rpc;

import org.apache.commons.lang3.StringUtils;
import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.exception.JawsServiceException;

import java.io.File;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Created by shenhongxi on 2020/6/14.
 */
public class URL {

    public static final String BACKUP_KEY = "backup";

    private String protocol;

    private String host;

    private int port;

    private String path;

    private final Map<String, String> parameters;

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

    public URL createCopy() {
        Map<String, String> params = new HashMap<>();
        if (this.parameters != null) {
            params.putAll(this.parameters);
        }

        return new URL(protocol, host, port, path, params);
    }

    public static URL valueOf(String url) {
        if (StringUtils.isBlank(url)) {
            throw new JawsServiceException("url is null");
        }
        String protocol = null;
        String host = null;
        int port = 0;
        String path = null;
        Map<String, String> parameters = new HashMap<>();
        int i = url.indexOf("?"); // separator between body and parameters
        if (i >= 0) {
            String[] parts = url.substring(i + 1).split("&");

            for (String part : parts) {
                part = part.trim();
                if (!part.isEmpty()) {
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
        if (i > 0) {
            protocol = url.substring(0, i);
            url = url.substring(i + 3);
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
        if (!url.isEmpty()) host = url;
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

    public boolean getParameter(String name, boolean defaultValue) {
        String value = getParameter(name);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }

    public int getParameter(String name, int defaultValue) {
        String value = getParameter(name);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        return Integer.parseInt(value);
    }

    public long getParameter(String name, long defaultValue) {
        String value = getParameter(name);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        return Long.parseLong(value);
    }

    public String getParameter(URLParamType paramType) {
        return getParameter(paramType.getName(), paramType.value());
    }

    public boolean getBoolParameter(URLParamType paramType) {
        return getParameter(paramType.getName(), paramType.boolValue());
    }

    public int getIntParameter(URLParamType paramType) {
        return getParameter(paramType.getName(), paramType.intValue());
    }

    public long getLongParameter(URLParamType paramType) {
        return getParameter(paramType.getName(), paramType.longValue());
    }

    public void addParameters(Map<String, String> params) {
        parameters.putAll(params);
    }

    public void addParameter(String name, String value) {
        if (StringUtils.isEmpty(name) || StringUtils.isEmpty(value)) {
            return;
        }
        parameters.put(name, value);
    }

    public void removeParameter(String name) {
        if (name != null) {
            parameters.remove(name);
        }
    }

    public Integer getMethodParameter(String methodName, String paramDesc, String name, int defaultValue) {
        String value = getMethodParameter(methodName, paramDesc, name);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        return Integer.parseInt(value);
    }

    public String getMethodParameter(String methodName, String paramDesc, String name) {
        String value = getParameter(JawsConstants.METHOD_CONFIG_PREFIX + methodName + "(" + paramDesc + ")." + name);
        if (value == null || value.isEmpty()) {
            return getParameter(name);
        }
        return value;
    }

    public String getHostPort() {
        if (this.port <= 0) {
            return host;
        }

        int idx = host.indexOf(":");
        if (idx < 0) {
            return host + ":" + this.port;
        }

        int port = Integer.parseInt(host.substring(idx + 1));
        if (port <= 0) {
            return host.substring(0, idx + 1) + ":" + this.port;
        }

        return host;
    }

    /**
     * Get the address string including all backup nodes, formatted as host:port,backup1:port1,backup2:port2
     */
    public String getBackupAddress() {
        StringBuilder address = new StringBuilder(host + ":" + port);
        String backup = getParameter(BACKUP_KEY);
        if (backup != null && !backup.isEmpty()) {
            address.append(',').append(backup);
        }
        return address.toString();
    }

    /**
     * Get the list of URLs including all backup nodes. The first is this URL, the rest are backup node URLs.
     */
    public List<URL> getBackupUrls() {
        List<URL> urls = new ArrayList<>();
        urls.add(this);
        String backup = getParameter(BACKUP_KEY);
        if (backup != null && !backup.isEmpty()) {
            String[] backups = JawsConstants.COMMA_SPLIT_PATTERN.split(backup);
            for (String bk : backups) {
                String[] hostPort = bk.split(":");
                URL backupUrl = new URL(this.protocol, hostPort[0].trim(),
                        hostPort.length > 1 ? Integer.parseInt(hostPort[1].trim()) : this.port,
                        this.path, new HashMap<>(this.parameters));
                backupUrl.removeParameter(BACKUP_KEY);
                urls.add(backupUrl);
            }
        }
        return urls;
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
        this.path = path;
    }

    public String getVersion() {
        return getParameter(URLParamType.version.getName(), URLParamType.version.value());
    }

    public String getGroup() {
        return getParameter(URLParamType.group.getName(), URLParamType.group.value());
    }

    public String getApplication() {
        return getParameter(URLParamType.application.getName(), URLParamType.application.value());
    }

    public String getModule() {
        return getParameter(URLParamType.module.getName(), URLParamType.module.value());
    }

    public String getUri() {
        return protocol + JawsConstants.PROTOCOL_SEPARATOR + host + ":" + port
                + File.separator + path;
    }

    /**
     * Return a service or reference identity. If two URLs have the same identity,
     * they represent the same service or reference.
     *
     * @return the identity string
     */
    public String getIdentity() {
        return protocol + JawsConstants.PROTOCOL_SEPARATOR + host + ":" + port +
                "/" + getParameter(URLParamType.group.getName(), URLParamType.group.value()) + "/" +
                getPath() + "/" + getParameter(URLParamType.version.getName(), URLParamType.version.value()) +
                "/" + getParameter(URLParamType.nodeType.getName(), URLParamType.nodeType.value());
    }

    /**
     * Check if this url can serve the refUrl.
     */
    public boolean canServe(URL refUrl) {
        if (refUrl == null || !this.getPath().equals(refUrl.getPath())) {
            return false;
        }

        if (!Objects.equals(protocol, refUrl.protocol)) {
            return false;
        }

        if (!StringUtils.equals(this.getParameter(URLParamType.nodeType.getName()), JawsConstants.NODE_TYPE_SERVICE)) {
            return false;
        }

        String version = getParameter(URLParamType.version.getName(), URLParamType.version.value());
        String refVersion = refUrl.getParameter(URLParamType.version.getName(), URLParamType.version.value());
        if (!version.equals(refVersion)) {
            return false;
        }
        // check group
        String group = getParameter(URLParamType.group.getName(), URLParamType.group.value());
        String refGroup = refUrl.getParameter(URLParamType.group.getName(), URLParamType.group.value());
        return group.equals(refGroup);
    }

    @Override
    public int hashCode() {
        int factor = 31;
        int result = 1;
        result = factor * result + Objects.hashCode(protocol);
        result = factor * result + Objects.hashCode(host);
        result = factor * result + Objects.hashCode(port);
        result = factor * result + Objects.hashCode(path);
        result = factor * result + Objects.hashCode(parameters);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof URL ou)) {
            return false;
        }
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

    /**
     * Includes protocol, host, port, path, and group
     */
    public String toSimpleString() {
        return getUri() + "?group=" + getGroup();
    }

    public String toFullStr() {
        StringBuilder builder = new StringBuilder();
        builder.append(getUri()).append("?");

        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();

            builder.append(name).append("=").append(value).append("&");
        }

        return builder.toString();
    }
}
