package org.hongxi.summer.common;

/**
 * Created by shenhongxi on 2020/6/27.
 */
public enum URLParamType {

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

    /************************** SPI start ******************************/

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

    public String getValue() {
        return value;
    }

    public int getIntValue() {
        return intValue;
    }

    public long getLongValue() {
        return longValue;
    }

    public boolean getBoolValue() {
        return boolValue;
    }
}
