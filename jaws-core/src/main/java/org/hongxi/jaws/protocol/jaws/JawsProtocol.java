package org.hongxi.jaws.protocol.jaws;

import org.apache.commons.lang3.StringUtils;
import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.common.extension.SpiMeta;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.protocol.AbstractProtocol;
import org.hongxi.jaws.rpc.*;
import org.hongxi.jaws.transport.ProviderMessageRouter;
import org.hongxi.jaws.transport.TransportException;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Created by shenhongxi on 2021/4/21.
 */
@SpiMeta(name = "jaws")
public class JawsProtocol extends AbstractProtocol {

    public static final String DEFAULT_CODEC = "jaws";
    private ConcurrentHashMap<String, ProviderMessageRouter> ipPort2RequestRouter = new ConcurrentHashMap<>();

    @Override
    protected <T> Exporter<T> createExporter(Provider<T> provider, URL url) {
        setDefaultCodec(url);
        return new DefaultRpcExporter<T>(provider, url, this.ipPort2RequestRouter, this.exporterMap);
    }

    @Override
    protected <T> Referer<T> createReferer(Class<T> clz, URL url, URL serviceUrl) {
        setDefaultCodec(url);
        return new V2RpcReferer<T>(clz, url, serviceUrl);
    }

    private void setDefaultCodec(URL url) {
        String codec = url.getParameter(URLParamType.codec.getName());
        if (StringUtils.isBlank(codec)) {
            url.getParameters().put(URLParamType.codec.getName(), DEFAULT_CODEC);
        }
    }

    /**
     * rpc referer
     *
     * @param <T>
     */
    class V2RpcReferer<T> extends DefaultRpcReferer<T> {

        public V2RpcReferer(Class<T> clz, URL url, URL serviceUrl) {
            super(clz, url, serviceUrl);
        }

        @Override
        protected Response doCall(Request request) {
            try {
                // use server end group
                request.setAttachment(URLParamType.group.getName(), serviceUrl.getGroup());
                request.setAttachment(JawsConstants.JAWS_PROXY_PROTOCOL, this.url.getProtocol()); // add proxy protocol for request agent
                return client.request(request);
            } catch (TransportException exception) {
                throw new JawsServiceException("DefaultRpcReferer call Error: url=" + url.getUri(), exception);
            }
        }

    }
}