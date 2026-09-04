package org.hongxi.jaws.config;

import java.io.Serial;
import java.util.Map;

/**
 * Configuration of a single RPC protocol, covering endpoint address (host,
 * port), serialization and codec selection, transport factory, and
 * server/client tuning knobs such as worker thread pool, queue size,
 * max connections, max content length, and heartbeat interval.
 * <p>
 * A heartbeat value of {@code 0} disables heartbeat detection.
 *
 * @see ServiceConfig
 * @see ReferenceConfig
 *
 * <p>
 * Created by shenhongxi on 2021/3/5.
 */
public class ProtocolConfig extends BaseConfig {

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
     * The transport factory.
     */
    protected String transportFactory;

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

    // server & client side

    /**
     * The maximum content length.
     */
    protected Integer maxContentLength;

    /**
     * Heartbeat interval in milliseconds. 0 means disabled.
     */
    protected Long heartbeat;

    // ---- TLS ----

    /**
     * Path to the X.509 certificate chain PEM file (server-side, or client mutual TLS).
     */
    protected String sslCertChain;

    /**
     * Path to the PKCS#8 private key PEM file.
     */
    protected String sslPrivateKey;

    /**
     * Path to the trusted CA certificate PEM file (client-side TLS).
     * When set, the client verifies the server certificate against this CA.
     */
    protected String sslTrustCert;

    // ---- Client multi-connection ----

    /**
     * Number of HTTP/2 connections to maintain per remote URL.
     * Default 1 (single multiplexed connection). Increasing this value
     * can help distribute load across backends behind an L4 load balancer.
     */
    protected Integer connections;

    /**
     * Message compression encoding for the wire protocol ({@code identity}
     * or {@code gzip}). Applied to outbound messages; inbound compressed
     * messages are always accepted. Ignored by non-wire protocols.
     */
    protected String compression;

    @Override
    protected void collectParams(Map<String, String> params) {
        putIfPresent(params, "protocol", name);
        putIfPresent(params, "serialization", serialization);
        putIfPresent(params, "codec", codec);
        putIfPresent(params, "transportFactory", transportFactory);
        putIfPresent(params, "maxServerConnections", maxServerConnections);
        putIfPresent(params, "minWorkerThreads", minWorkerThreads);
        putIfPresent(params, "maxWorkerThreads", maxWorkerThreads);
        putIfPresent(params, "workerQueueSize", workerQueueSize);
        putIfPresent(params, "maxContentLength", maxContentLength);
        putIfPresent(params, "heartbeat", heartbeat);
        putIfPresent(params, "sslCertChain", sslCertChain);
        putIfPresent(params, "sslPrivateKey", sslPrivateKey);
        putIfPresent(params, "sslTrustCert", sslTrustCert);
        putIfPresent(params, "connections", connections);
        putIfPresent(params, "compression", compression);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

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

    public String getTransportFactory() {
        return transportFactory;
    }

    public void setTransportFactory(String transportFactory) {
        this.transportFactory = transportFactory;
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

    // --- server & client shared getter/setter ---

    public Integer getMaxContentLength() {
        return maxContentLength;
    }

    public void setMaxContentLength(Integer maxContentLength) {
        this.maxContentLength = maxContentLength;
    }

    public Long getHeartbeat() {
        return heartbeat;
    }

    public void setHeartbeat(Long heartbeat) {
        this.heartbeat = heartbeat;
    }

    // --- TLS getter/setter ---

    public String getSslCertChain() {
        return sslCertChain;
    }

    public void setSslCertChain(String sslCertChain) {
        this.sslCertChain = sslCertChain;
    }

    public String getSslPrivateKey() {
        return sslPrivateKey;
    }

    public void setSslPrivateKey(String sslPrivateKey) {
        this.sslPrivateKey = sslPrivateKey;
    }

    public String getSslTrustCert() {
        return sslTrustCert;
    }

    public void setSslTrustCert(String sslTrustCert) {
        this.sslTrustCert = sslTrustCert;
    }

    // --- Client multi-connection getter/setter ---

    public Integer getConnections() {
        return connections;
    }

    public void setConnections(Integer connections) {
        this.connections = connections;
    }

    // --- Wire compression getter/setter ---

    public String getCompression() {
        return compression;
    }

    public void setCompression(String compression) {
        this.compression = compression;
    }
}