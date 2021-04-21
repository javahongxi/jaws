package org.hongxi.jaws.mock;

import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.Channel;
import org.hongxi.jaws.transport.TransportException;

import java.net.InetSocketAddress;

/**
 * Created by shenhongxi on 2021/4/21.
 */
public class MockChannel implements Channel {
    private URL url;

    public MockChannel(URL url) {
        this.url = url;
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return null;
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        return null;
    }

    @Override
    public Response request(Request request) throws TransportException {
        return null;
    }

    @Override
    public boolean open() {
        return false;
    }

    @Override
    public void close() {}

    @Override
    public void close(int timeout) {}

    @Override
    public boolean isClosed() {
        return false;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public URL getUrl() {
        return url;
    }

}
