package org.hongxi.summer.transport.netty;

import org.hongxi.summer.rpc.Request;
import org.hongxi.summer.rpc.Response;
import org.hongxi.summer.rpc.URL;
import org.hongxi.summer.transport.AbstractSharedPoolClient;
import org.hongxi.summer.transport.TransportException;

/**
 * Created by shenhongxi on 2020/7/28.
 */
public class NettyClient extends AbstractSharedPoolClient {

    public NettyClient(URL url) {
        super(url);
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
        return null;
    }
}
