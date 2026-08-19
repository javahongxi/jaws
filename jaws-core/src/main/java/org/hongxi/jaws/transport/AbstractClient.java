package org.hongxi.jaws.transport;

import org.hongxi.jaws.codec.Codec;
import org.hongxi.jaws.common.ChannelState;
import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.common.extension.ExtensionLoader;
import org.hongxi.jaws.common.util.JawsFrameworkUtils;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Created by shenhongxi on 2020/7/28.
 */
public abstract class AbstractClient implements Client {
    private static final Logger log = LoggerFactory.getLogger(AbstractClient.class);

    protected URL url;
    protected Codec codec;

    protected volatile ChannelState state = ChannelState.UNINIT;

    public AbstractClient(URL url) {
        this.url = url;
        this.codec =
                ExtensionLoader.getExtensionLoader(Codec.class).getExtension(
                        url.getParameter(URLParamType.codec.getName(), URLParamType.codec.value()));
        log.info("init netty client. url: " + url.getHost() + "-" + url.getPath() + ", use codec: " + codec.getClass().getSimpleName());
    }

    @Override
    public void heartbeat(Request request) {
        throw new JawsFrameworkException("heartbeat not support: " + JawsFrameworkUtils.toString(request));
    }
}
