package org.hongxi.jaws.transport;

import org.hongxi.jaws.codec.Codec;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.common.extension.ExtensionLoader;
import org.hongxi.jaws.rpc.URL;

/**
 * Base implementation of {@link Server} holding state shared by all
 * server transports: the bound {@link URL}, the {@link Codec} resolved
 * from the URL parameter via the extension loader, and the volatile
 * {@link ChannelState} lifecycle flag.
 * <p>
 * Concrete transports such as {@link org.hongxi.jaws.transport.netty.NettyServer}
 * extend this class to provide accepting connections and dispatching requests.
 * <p>
 * Created by shenhongxi on 2020/6/25.
 */
public abstract class AbstractServer implements Server {

    protected URL url;
    protected Codec codec;

    protected volatile ChannelState state = ChannelState.UNINIT;

    public AbstractServer() {
    }

    public AbstractServer(URL url) {
        this.url = url;
        this.codec = ExtensionLoader.getExtensionLoader(Codec.class)
                .getExtension(url.getParameter(UrlParam.Transport.CODEC));
    }

    public void setUrl(URL url) {
        this.url = url;
    }

    public void setCodec(Codec codec) {
        this.codec = codec;
    }
}
