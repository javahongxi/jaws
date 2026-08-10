package org.hongxi.jaws.config;

import java.io.Serial;

/**
 * registry config
 * <p>
 * Created by shenhongxi on 2021/3/5.
 */

public class RegistryConfig extends AbstractConfig {

    @Serial
    private static final long serialVersionUID = 3236055928361714933L;

    // 注册协议
    private String protocol;

    // 注册中心地址，支持多个ip+port，格式：(protocol://)ip1:port1,ip2:port2,ip3，如果没有port，则使用默认的port
    private String address;

    // 注册中心缺省端口
    private Integer port;

    // 注册中心用户名（如Nacos鉴权）
    private String username;

    // 注册中心密码（如Nacos鉴权）
    private String password;

    // 注册中心连接超时时间(毫秒)
    private Integer connectTimeout;

    // 注册中心会话超时时间(毫秒)
    private Integer registrySessionTimeout;

    // 失败后重试的时间间隔
    private Integer registryRetryPeriod;

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

    public Integer getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Integer connectTimeout) {
        this.connectTimeout = connectTimeout;
    }
}