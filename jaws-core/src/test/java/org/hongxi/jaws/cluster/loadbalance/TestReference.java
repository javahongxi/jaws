package org.hongxi.jaws.cluster.loadbalance;

import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.URL;

/**
 * 负载均衡单元测试用的 Reference 轻量级 stub
 */
class TestReference implements Reference<String> {

    private final String name;
    private volatile boolean available = true;
    private volatile int activeCount = 0;

    TestReference(String name) {
        this.name = name;
    }

    TestReference(String name, int activeCount) {
        this.name = name;
        this.activeCount = activeCount;
    }

    void setAvailable(boolean available) {
        this.available = available;
    }

    void setActiveCount(int activeCount) {
        this.activeCount = activeCount;
    }

    String getName() {
        return name;
    }

    @Override
    public int activeReferenceCount() {
        return activeCount;
    }

    @Override
    public URL getServiceUrl() {
        return null;
    }

    @Override
    public Class<String> getInterface() {
        return String.class;
    }

    @Override
    public Response call(Request request) {
        return null;
    }

    @Override
    public void init() {}

    @Override
    public void destroy() {}

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public String desc() {
        return name;
    }

    @Override
    public URL getUrl() {
        return null;
    }

    @Override
    public String toString() {
        return "TestReference[" + name + "]";
    }
}
