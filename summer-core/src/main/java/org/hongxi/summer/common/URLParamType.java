package org.hongxi.summer.common;

/**
 * Created by shenhongxi on 2020/6/27.
 */
public enum URLParamType {

    version("version", SummerConstants.DEFAULT_VERSION),

    minWorkerThreads("minWorkerThreads", 20),

    maxWorkerThreads("maxWorkerThreads", 200),

    maxContentLength("maxContentLength", 10 * 1024 * 1024),

    maxServerConnections("maxServerConnections", 100000),
    /**
     * multi referer share the same channel
     */
    shareChannel("shareChannel", false),

    /************************** SPI start ******************************/

    codec("codec", "summer"),

    /************************** SPI end ******************************/

    group("group", "default_rpc"),

    nodeType("nodeType", SummerConstants.NODE_TYPE_SERVICE),

    workerQueueSize("workerQueueSize", 0);

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
