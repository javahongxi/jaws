package org.hongxi.jaws.protocol.jaws;

import org.apache.commons.lang3.StringUtils;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.common.extension.Extension;
import org.hongxi.jaws.protocol.AbstractProtocol;
import org.hongxi.jaws.rpc.*;

/**
 * Created by shenhongxi on 2021/4/21.
 */
@Extension("jaws")
public class JawsProtocol extends AbstractProtocol {

    public static final String DEFAULT_CODEC = "jaws";

    @Override
    protected <T> Exporter<T> createExporter(Provider<T> provider) {
        URL url = provider.getUrl();
        setDefaultCodec(url);
        return new DefaultRpcExporter<>(provider, url);
    }

    @Override
    protected <T> Reference<T> createReference(Class<T> interfaceClass, URL url) {
        setDefaultCodec(url);
        return new DefaultRpcReference<>(interfaceClass, url);
    }

    private void setDefaultCodec(URL url) {
        String codec = url.getParameter(UrlParam.Transport.CODEC.getName());
        if (StringUtils.isBlank(codec)) {
            url.getParameters().put(UrlParam.Transport.CODEC.getName(), DEFAULT_CODEC);
        }
    }
}