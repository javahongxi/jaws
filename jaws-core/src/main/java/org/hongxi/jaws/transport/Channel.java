package org.hongxi.jaws.transport;

import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.URL;

import java.net.InetSocketAddress;

/**
 * Created by shenhongxi on 2020/6/14.
 */
public interface Channel {

    InetSocketAddress getLocalAddress();

    InetSocketAddress getRemoteAddress();

    Response request(Request request) throws TransportException;

    boolean open();

    void close();

    void close(int timeout);

    boolean isClosed();

    boolean isAvailable();

    URL getUrl();
}
