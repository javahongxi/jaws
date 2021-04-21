package org.hongxi.jaws.mock;

import org.hongxi.jaws.rpc.DefaultResponse;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.Client;
import org.hongxi.jaws.transport.TransportException;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by shenhongxi on 2021/4/21.
 */
public class MockClient implements Client {

    public static Response mockResponse;
    public static ConcurrentHashMap<URL, AtomicInteger> urlMap = new ConcurrentHashMap<>();
    URL url;

    public MockClient(URL url) {
        this.url = url;
        urlMap.putIfAbsent(url, new AtomicInteger());
        mockResponse = new DefaultResponse();
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
        urlMap.get(url).incrementAndGet();
        // TODO 根据不同request 返回指定repsonse
        return mockResponse;
    }

    @Override
    public boolean open() {

        return true;
    }

    @Override
    public void close() {


    }

    @Override
    public void close(int timeout) {


    }

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

    @Override
    public void heartbeat(Request request) {}


}
