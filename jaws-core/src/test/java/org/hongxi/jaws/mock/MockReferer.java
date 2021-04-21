package org.hongxi.jaws.mock;

import org.hongxi.jaws.rpc.Referer;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.URL;

/**
 * Created by shenhongxi on 2021/4/21.
 */
public class MockReferer<T> implements Referer<T> {
    public Class<T> clz = null;
    public int active = 0;
    public boolean available = true;
    public String desc = this.getClass().getSimpleName();
    public URL url = null;
    public URL serviceUrl = null;

    public MockReferer() {

    }

    public MockReferer(URL serviceUrl) {
        this.serviceUrl = serviceUrl;
    }

    @Override
    public Class<T> getInterface() {
        return clz;
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
        return desc;
    }

    @Override
    public URL getUrl() {
        return url;
    }

    @Override
    public int activeRefererCount() {
        return active;
    }

    @Override
    public URL getServiceUrl() {
        return serviceUrl;
    }

}
