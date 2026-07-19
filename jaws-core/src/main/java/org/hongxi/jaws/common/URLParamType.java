package org.hongxi.jaws.common;

import org.hongxi.jaws.config.RegistryConfig;

/**
 * Created by shenhongxi on 2020/6/27.
 */
public enum URLParamType {

    version("version", JawsConstants.DEFAULT_VERSION),

    requestTimeout("requestTimeout", 200),
    /** request id from http interface */
    requestIdFromClient("requestIdFromClient", 0),

    connectTimeout("connectTimeout", 1000),

    minWorkerThreads("minWorkerThreads", 20),

    maxWorkerThreads("maxWorkerThreads", 200),

    maxContentLength("maxContentLength", 10 * 1024 * 1024),

    maxServerConnections("maxServerConnections", 100000),

    minClientConnections("minClientConnections", 2),
    maxClientConnections("maxClientConnections", 10),
    maxConnectionsPerGroup("maxConnectionsPerGroup", 0),

    registryRetryPeriod("registryRetryPeriod", 30 * JawsConstants.SECOND_MILLS),
    /** how to excise unavailable nodes from registry */
    excise("excise", RegistryConfig.Excise.DYNAMIC.getName()),
    cluster("cluster", JawsConstants.DEFAULT_VALUE),
    loadbalance("loadbalance", "leastActive"),
    haStrategy("haStrategy", "failover"),
    protocol("protocol", JawsConstants.PROTOCOL_JAWS),
    path("path", ""),
    host("host", ""),
    port("port", 0),
    proxy("proxy", JawsConstants.PROXY_JDK),
    filter("filter", ""),

    /** multi services share the same channel (port) */
    shareChannel("shareChannel", true),
    asyncInitConnection("asyncInitConnection", false),
    fusingThreshold("fusingThreshold", 10),

    heartbeatFactory("heartbeatFactory", "jaws"),

    /************************** SPI start ******************************/

    serialization("serialization", "hessian2"),

    codec("codec", "jaws"),
    endpointFactory("endpointFactory", "netty"),

    toggleService("toggleService", "localToggleService"),

    /************************** SPI end ******************************/

    group("group", "default_rpc"),
    clientGroup("clientGroup", "default_rpc"),
    accessLog("accessLog", false),

    refreshTimestamp("refreshTimestamp", 0),
    nodeType("nodeType", JawsConstants.NODE_TYPE_SERVICE),

    /** whether to enable gzip compression */
    gzip("gzip", false),
    /** minimum data size for gz compression, compress only when exceeding this threshold */
    minGzipSize("minGzipSize", 1000),

    application("application", JawsConstants.FRAMEWORK_NAME),
    module("module", JawsConstants.FRAMEWORK_NAME),

    retries("retries", 0),
    mock("mock", "false"),
    mean("mean", "2"),
    p90("p90", "4"),
    p99("p99", "10"),
    p999("p999", "70"),
    errorRate("errorRate", "0.01"),
    check("check", "true"),
    registrySessionTimeout("registrySessionTimeout", 1 * JawsConstants.MINUTE_MILLS),
    directUrl("directUrl", ""),

    register("register", true),
    subscribe("subscribe", true),
    throwException("throwException", "true"),
    transExceptionStack("transExceptionStack", true),

    /** weight ratio for each group when switching groups, no weight by default */
    weights("weights", ""),

    /** message processing dispatch strategy */
    providerProtectedStrategy("providerProtectedStrategy", "jaws"),

    workerQueueSize("workerQueueSize", 0),

    /** interval in milliseconds between failback retry attempts */
    failbackPeriod("failbackPeriod", 5000),

    /** Graceful shutdown timeout in milliseconds. During this period, the server stops accepting
     *  new requests and waits for in-flight requests to complete before closing connections. */
    gracefulShutdownTimeout("gracefulShutdownTimeout", 10000);

    private String name;
    private String value;
    private int intValue;
    private long longValue;
    private boolean boolValue;

    URLParamType(String name, String value) {
        this.name = name;
        this.value = value;
    }

    URLParamType(String name, int intValue) {
        this.name = name;
        this.value = String.valueOf(intValue);
        this.intValue = intValue;
    }

    URLParamType(String name, long longValue) {
        this.name = name;
        this.value = String.valueOf(longValue);
        this.longValue = longValue;
    }

    URLParamType(String name, boolean boolValue) {
        this.name = name;
        this.value = String.valueOf(boolValue);
        this.boolValue = boolValue;
    }

    public String getName() {
        return name;
    }

    public String value() {
        return value;
    }

    public int intValue() {
        return intValue;
    }

    public long longValue() {
        return longValue;
    }

    public boolean boolValue() {
        return boolValue;
    }
}
