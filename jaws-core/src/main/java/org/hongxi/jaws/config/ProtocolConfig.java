package org.hongxi.jaws.config;

import org.hongxi.jaws.config.annotation.ConfigDesc;
import java.io.Serial;

/**
 * Created by shenhongxi on 2021/3/5.
 */
public class ProtocolConfig extends AbstractConfig {

    @Serial
    private static final long serialVersionUID = 7605496816982926360L;

    // 服务协议
    private String name;
    // 服务host
    private String host;
    // 服务port
    private Integer port;
    // 序列化方式
    private String serialization;
    // 协议编码
    private String codec;
    // endpoint factory
    protected String endpointFactory;

    // server端
    // server支持的最大连接数
    protected Integer maxServerConnections;
    // 最小工作pool线程数
    protected Integer minWorkerThreads;
    // 最大工作pool线程数
    protected Integer maxWorkerThreads;
    // server worker queue size
    protected Integer workerQueueSize;

    // client端
    // client最小连接数
    protected Integer minClientConnections;
    // client最大连接数
    protected Integer maxClientConnections;
    protected Integer maxConnectionsPerGroup;

    // server & client共用
    // 请求响应包的最大长度限制
    protected Integer maxContentLength;

    @ConfigDesc(key = "protocol", required = true)
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @ConfigDesc(excluded = true)
    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    @ConfigDesc(excluded = true)
    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getSerialization() {
        return serialization;
    }

    public void setSerialization(String serialization) {
        this.serialization = serialization;
    }

    public String getCodec() {
        return codec;
    }

    public void setCodec(String codec) {
        this.codec = codec;
    }

    public String getEndpointFactory() {
        return endpointFactory;
    }

    public void setEndpointFactory(String endpointFactory) {
        this.endpointFactory = endpointFactory;
    }

    // --- server端 getter/setter ---

    public Integer getMaxServerConnections() {
        return maxServerConnections;
    }

    public void setMaxServerConnections(Integer maxServerConnections) {
        this.maxServerConnections = maxServerConnections;
    }

    public Integer getMinWorkerThreads() {
        return minWorkerThreads;
    }

    public void setMinWorkerThreads(Integer minWorkerThreads) {
        this.minWorkerThreads = minWorkerThreads;
    }

    public Integer getMaxWorkerThreads() {
        return maxWorkerThreads;
    }

    public void setMaxWorkerThreads(Integer maxWorkerThreads) {
        this.maxWorkerThreads = maxWorkerThreads;
    }

    public Integer getWorkerQueueSize() {
        return workerQueueSize;
    }

    public void setWorkerQueueSize(Integer workerQueueSize) {
        this.workerQueueSize = workerQueueSize;
    }

    // --- client端 getter/setter ---

    public Integer getMinClientConnections() {
        return minClientConnections;
    }

    public void setMinClientConnections(Integer minClientConnections) {
        this.minClientConnections = minClientConnections;
    }

    public Integer getMaxClientConnections() {
        return maxClientConnections;
    }

    public void setMaxClientConnections(Integer maxClientConnections) {
        this.maxClientConnections = maxClientConnections;
    }

    public Integer getMaxConnectionsPerGroup() {
        return maxConnectionsPerGroup;
    }

    public void setMaxConnectionsPerGroup(Integer maxConnectionsPerGroup) {
        this.maxConnectionsPerGroup = maxConnectionsPerGroup;
    }

    // --- server & client共用 getter/setter ---

    public Integer getMaxContentLength() {
        return maxContentLength;
    }

    public void setMaxContentLength(Integer maxContentLength) {
        this.maxContentLength = maxContentLength;
    }
}