package org.hongxi.jaws.transport;

import org.hongxi.jaws.codec.Codec;
import org.hongxi.jaws.common.ChannelState;
import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.common.extension.ExtensionLoader;
import org.hongxi.jaws.rpc.URL;

/**
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
                .getExtension(url.getParameter(URLParamType.codec));
    }

    public void setUrl(URL url) {
        this.url = url;
    }

    public void setCodec(Codec codec) {
        this.codec = codec;
    }
}
