package org.hongxi.jaws.mock;

import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.Channel;
import org.hongxi.jaws.transport.Server;
import org.hongxi.jaws.transport.TransportException;

import java.net.InetSocketAddress;
import java.util.Collection;

/**
 * Created by shenhongxi on 2021/4/21.
 */
public class MockServer implements Server {
    URL url;

    public MockServer(URL url) {
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

        return false;
    }

    @Override
    public URL getUrl() {
        return url;
    }

    @Override
    public boolean isBound() {

        return false;
    }

    @Override
    public Collection<Channel> getChannels() {

        return null;
    }

    @Override
    public Channel getChannel(InetSocketAddress remoteAddress) {

        return null;
    }

}
