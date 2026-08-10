package org.hongxi.jaws.config;

import org.hongxi.jaws.config.annotation.ConfigDesc;
import java.io.Serial;

/**
 * Created by shenhongxi on 2021/3/5.
 */
public class ProtocolConfig extends AbstractConfig {

    @Serial
    private static final long serialVersionUID = 7605496816982926360L;

    /**
     * The name of the protocol.
     */
    private String name;

    /**
     * The service's IP address (useful when there are multiple network cards available).
     */
    private String host;

    /**
     * The service's port number.
     */
    private Integer port;

    /**
     * The serialization method.
     */
    private String serialization;

    /**
     * The protocol codec.
     */
    private String codec;

    /**
     * The endpoint factory.
     */
    protected String endpointFactory;

    // server side

    /**
     * The maximum number of server connections.
     */
    protected Integer maxServerConnections;

    /**
     * The minimum number of worker threads.
     */
    protected Integer minWorkerThreads;

    /**
     * The maximum number of worker threads.
     */
    protected Integer maxWorkerThreads;

    /**
     * The worker queue size.
     */
    protected Integer workerQueueSize;

    // client side

    /**
     * The minimum number of client connections.
     */
    protected Integer minClientConnections;

    /**
     * The maximum number of client connections.
     */
    protected Integer maxClientConnections;

    /**
     * The maximum number of connections per group.
     */
    protected Integer maxConnectionsPerGroup;

    // server & client side

    /**
     * The maximum content length.
     */
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

    // --- server-only getter/setter ---

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

    // --- client-only getter/setter ---

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

    // --- server & client shared getter/setter ---

    public Integer getMaxContentLength() {
        return maxContentLength;
    }

    public void setMaxContentLength(Integer maxContentLength) {
        this.maxContentLength = maxContentLength;
    }
}