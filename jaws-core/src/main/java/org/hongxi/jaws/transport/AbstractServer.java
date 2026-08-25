package org.hongxi.jaws.transport;

import org.hongxi.jaws.rpc.URL;

/**
 * Base implementation of {@link Server} holding state shared by all
 * server transports: the bound {@link URL} and the volatile
 * {@link ChannelState} lifecycle flag.
 * <p>
 * Concrete transports such as {@link org.hongxi.jaws.transport.netty.NettyServer}
 * and {@link org.hongxi.jaws.transport.http2.Http2Server} extend this class
 * to provide accepting connections and dispatching requests.
 * <p>
 * Created by shenhongxi on 2020/6/25.
 */
public abstract class AbstractServer implements Server {

    protected final URL url;

    protected volatile ChannelState state = ChannelState.UNINIT;

    protected AbstractServer(URL url) {
        this.url = url;
    }

    @Override
    public URL getUrl() {
        return url;
    }

    @Override
    public boolean isAvailable() {
        return state.isAliveState();
    }
}
