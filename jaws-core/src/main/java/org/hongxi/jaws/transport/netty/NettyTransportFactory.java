package org.hongxi.jaws.transport.netty;

import org.hongxi.jaws.common.extension.Extension;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.Client;
import org.hongxi.jaws.transport.MessageHandler;
import org.hongxi.jaws.transport.Server;
import org.hongxi.jaws.transport.AbstractTransportFactory;

import java.util.Set;

/**
 * {@code netty} {@link org.hongxi.jaws.common.extension.Extension} of
 * {@link AbstractTransportFactory} that instantiates the Netty transport
 * implementation: a {@link NettyServer} with its business thread pool for
 * the provider side, and a {@link NettyClient} for the consumer side.
 *
 * @see NettyServer
 * @see NettyClient
 * <p>
 * Created by shenhongxi on 2020/7/31.
 */
@Extension("netty")
public class NettyTransportFactory extends AbstractTransportFactory {

    @Override
    public Set<String> supportedProtocols() {
        // The jaws binary protocol (0x4A57 magic framing) over Netty TCP
        return Set.of("jaws");
    }

    @Override
    protected Server innerCreateServer(URL url, MessageHandler messageHandler) {
        return new NettyServer(url, messageHandler);
    }

    @Override
    protected Client innerCreateClient(URL url) {
        return new NettyClient(url);
    }
}
