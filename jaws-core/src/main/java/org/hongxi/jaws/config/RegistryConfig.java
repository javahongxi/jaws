package org.hongxi.jaws.config;

import java.io.Serial;

import java.util.Map;

/**
 * Created by shenhongxi on 2021/3/5.
 */
public class RegistryConfig extends AbstractConfig {

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
}