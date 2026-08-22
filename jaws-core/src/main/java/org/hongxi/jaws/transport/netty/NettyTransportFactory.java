package org.hongxi.jaws.transport.netty;

import org.hongxi.jaws.common.extension.Extension;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.Client;
import org.hongxi.jaws.transport.MessageHandler;
import org.hongxi.jaws.transport.Server;
import org.hongxi.jaws.transport.AbstractTransportFactory;

/**
 * Created by shenhongxi on 2020/7/31.
 */
@Extension(name = "netty")
public class NettyTransportFactory extends AbstractTransportFactory {
    @Override
    protected Server innerCreateServer(URL url, MessageHandler messageHandler) {
        return new NettyServer(url, messageHandler);
    }

    @Override
    protected Client innerCreateClient(URL url) {
        return new NettyClient(url);
    }
}
