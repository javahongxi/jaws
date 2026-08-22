package org.hongxi.jaws.transport;

import org.hongxi.jaws.codec.Codec;
import org.hongxi.jaws.common.ChannelState;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.common.extension.ExtensionLoader;
import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base implementation of {@link Client} holding state shared by all
 * client transports: the remote {@link URL}, the {@link Codec} resolved
 * from the URL parameter via the extension loader, and the volatile
 * {@link ChannelState} lifecycle flag.
 * <p>
 * Concrete transports such as {@link org.hongxi.jaws.transport.netty.NettyClient}
 * extend this class to provide connection management and request sending.
 * <p>
 * Created by shenhongxi on 2020/7/28.
 */
public abstract class AbstractClient implements Client {
    private static final Logger log = LoggerFactory.getLogger(AbstractClient.class);

    protected URL url;
    protected Codec codec;

    protected volatile ChannelState state = ChannelState.UNINIT;

    public AbstractClient(URL url) {
        this.url = url;
        this.codec = ExtensionLoader.getExtensionLoader(Codec.class)
                        .getExtension(url.getParameter(UrlParam.Transport.CODEC));
        log.info("init netty client. url: {}-{}, use codec: {}", 
                url.getHost(), url.getPath(), codec.getClass().getSimpleName());
    }
}
