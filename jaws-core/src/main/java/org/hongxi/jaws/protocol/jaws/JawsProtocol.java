package org.hongxi.jaws.protocol.jaws;

import org.apache.commons.lang3.StringUtils;
import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.common.extension.SpiMeta;
import org.hongxi.jaws.protocol.AbstractProtocol;
import org.hongxi.jaws.rpc.*;
import org.hongxi.jaws.transport.ProviderMessageRouter;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Created by shenhongxi on 2021/4/21.
 */
@SpiMeta(name = "jaws")
public class JawsProtocol extends AbstractProtocol {

    public static final String DEFAULT_CODEC = "jaws";
    private final ConcurrentHashMap<String, ProviderMessageRouter> ipPort2RequestRouter = new ConcurrentHashMap<>();

    @Override
    protected <T> Exporter<T> createExporter(Provider<T> provider, URL url) {
        setDefaultCodec(url);
        return new DefaultRpcExporter<>(provider, url, this.ipPort2RequestRouter, this.exporterMap);
    }

    @Override
    protected <T> Reference<T> createReference(Class<T> clazz, URL url, URL serviceUrl) {
        setDefaultCodec(url);
        return new DefaultRpcReference<>(clazz, url, serviceUrl);
    }

    private void setDefaultCodec(URL url) {
        String codec = url.getParameter(URLParamType.codec.getName());
        if (StringUtils.isBlank(codec)) {
            url.getParameters().put(URLParamType.codec.getName(), DEFAULT_CODEC);
        }
    }
}