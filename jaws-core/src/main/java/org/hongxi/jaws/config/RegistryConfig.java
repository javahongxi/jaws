package org.hongxi.jaws.config;

import java.io.Serial;

import java.util.Map;

/**
 * Configuration of a single registry center (protocol, address, port and
 * session/retry settings) used by both service export and reference subscribe.
 * <p>
 * Each {@code RegistryConfig} is converted into a registry {@code URL} from
 * which the corresponding {@link org.hongxi.jaws.registry.RegistryFactory}
 * SPI extension is resolved.
 *
 * @see ServiceConfig
 * @see ReferenceConfig
 *
 * <p>
 * Created by shenhongxi on 2021/3/5.
 */
public class RegistryConfig extends BaseConfig {

    @Serial
    private static final long serialVersionUID = 3236055928361714933L;

    /**
     * Protocol used for the register center.
     */
    private String protocol;

    /**
     * Register center address.
     */
    private String address;

    /**
     * Default port for the register center.
     */
    private Integer port;

    /**
     * Username to login the register center.
     */
    private String username;

    /**
     * Password to login the register center.
     */
    private String password;

    /**
     * Connect timeout in milliseconds for the register center.
     */
    private Integer connectTimeout;

    /**
     * Session timeout in milliseconds for the register center.
     */
    private Integer registrySessionTimeout;

    /**
     * Failed retry period in milliseconds for the register center.
     */
    private Integer registryRetryPeriod;

    /**
     * Whether to enable local file cache for registry disaster recovery.
     * When enabled, discovered service URLs are persisted to a local file so that
     * consumers can still find providers when the registry center is unavailable.
     */
    private boolean cacheEnabled = true;

    /**
     * Custom path for the registry local cache file.
     * If not set, defaults to ~/.jaws/registry/{app}-{address}.cache.
     */
    private String cacheFile;

    @Override
    protected void collectParams(Map<String, String> params) {
        putIfPresent(params, "protocol", protocol);
        putIfPresent(params, "address", address);
        putIfPresent(params, "port", port);
        putIfPresent(params, "username", username);
        putIfPresent(params, "password", password);
        putIfPresent(params, "connectTimeout", connectTimeout);
        putIfPresent(params, "registrySessionTimeout", registrySessionTimeout);
        putIfPresent(params, "registryRetryPeriod", registryRetryPeriod);
        putIfPresent(params, "cacheEnabled", cacheEnabled);
        putIfPresent(params, "cacheFile", cacheFile);
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public Integer getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Integer connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Integer getRegistrySessionTimeout() {
        return registrySessionTimeout;
    }

    public void setRegistrySessionTimeout(Integer registrySessionTimeout) {
        this.registrySessionTimeout = registrySessionTimeout;
    }

    public Integer getRegistryRetryPeriod() {
        return registryRetryPeriod;
    }

    public void setRegistryRetryPeriod(Integer registryRetryPeriod) {
        this.registryRetryPeriod = registryRetryPeriod;
    }

    public boolean isCacheEnabled() {
        return cacheEnabled;
    }

    public void setCacheEnabled(boolean cacheEnabled) {
        this.cacheEnabled = cacheEnabled;
    }

    public String getCacheFile() {
        return cacheFile;
    }

    public void setCacheFile(String cacheFile) {
        this.cacheFile = cacheFile;
    }
}