package org.hongxi.jaws.config;

import org.hongxi.jaws.config.annotation.ConfigDesc;
import java.io.Serial;

import java.util.Map;

/**
 * Created by shenhongxi on 2021/3/5.
 */
public class ProtocolConfig extends AbstractConfig {

    @Serial
    private static final long serialVersionUID = 7605496816982926360L;
    // client最小连接数
    protected Integer minClientConnections;
    // client最大连接数
    protected Integer maxClientConnections;
    protected Integer maxConnectionsPerGroup;
    // 最小工作pool线程数
    protected Integer minWorkerThreads;
    // 最大工作pool线程数
    protected Integer maxWorkerThreads;
    // 请求响应包的最大长度限制
    protected Integer maxContentLength;
    // server支持的最大连接数
    protected Integer maxServerConnections;
    // endpoint factory
    protected String endpointFactory;
    // 采用哪种cluster 的实现
    protected String cluster;
    // loadBalance 方式
    protected String loadBalance;
    // high available strategy
    protected String haStrategy;
    // server worker queue size
    protected Integer workerQueueSize;
    // 服务协议
    private String name;
    // 序列化方式
    private String serialization;
    // 协议编码
    private String codec;

    // 扩展参数
    private Map<String, String> parameters;

    @ConfigDesc(key = "protocol", required = true)
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSerialization() {
        return serialization;
    }

    public void setSerialization(String serialization) {
        this.serialization = serialization;
    }

    public Map<String, String> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, String> parameters) {
        this.parameters = parameters;
    }

    public String getCodec() {
        return codec;
    }

    public void setCodec(String codec) {
        this.codec = codec;
    }

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

    public Integer getMaxContentLength() {
        return maxContentLength;
    }

    public void setMaxContentLength(Integer maxContentLength) {
        this.maxContentLength = maxContentLength;
    }

    public Integer getMaxServerConnections() {
        return maxServerConnections;
    }

    public void setMaxServerConnections(Integer maxServerConnections) {
        this.maxServerConnections = maxServerConnections;
    }

    public String getEndpointFactory() {
        return endpointFactory;
    }

    public void setEndpointFactory(String endpointFactory) {
        this.endpointFactory = endpointFactory;
    }

    public String getCluster() {
        return cluster;
    }

    public void setCluster(String cluster) {
        this.cluster = cluster;
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

    public Integer getWorkerQueueSize() {
        return workerQueueSize;
    }

    public void setWorkerQueueSize(Integer workerQueueSize) {
        this.workerQueueSize = workerQueueSize;
    }

}