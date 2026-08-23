package org.hongxi.jaws.protocol;

import org.hongxi.jaws.common.extension.Extension;
import org.hongxi.jaws.common.util.RpcUtils;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.rpc.AbstractExporter;
import org.hongxi.jaws.rpc.AbstractReference;
import org.hongxi.jaws.rpc.Exporter;
import org.hongxi.jaws.rpc.Provider;
import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Intra-JVM invocation within the same node.
 *
 * <pre>
 *     1) Provider and reference correspond to each other
 *     2) Provider must be exported before being referenced by consumer
 * </pre>
 * <p>
 * Created by shenhongxi on 2021/4/21.
 *
 */
@Extension("injvm")
public class InjvmProtocol extends AbstractProtocol {

    private static final Logger log = LoggerFactory.getLogger(InjvmProtocol.class);

    @Override
    protected <T> Exporter<T> createExporter(Provider<T> provider) {
        return new InJvmExporter<>(provider);
    }

    @Override
    protected <T> Reference<T> createReference(Class<T> interfaceClass, URL url) {
        return new InjvmReference<>(interfaceClass, url);
    }

    /**
     * injvm provider
     *
     * @param <T>
     */
    class InJvmExporter<T> extends AbstractExporter<T> {
        public InJvmExporter(Provider<T> provider) {
            super(provider, provider.getUrl());
        }

        @Override
        protected boolean doInit() {
            return true;
        }

        @Override
        public void destroy() {
        }
    }

    /**
     * injvm consumer
     *
     * @param <T>
     */
    class InjvmReference<T> extends AbstractReference<T> {
        private Exporter<T> exporter;

        public InjvmReference(Class<T> interfaceClass, URL url) {
            super(interfaceClass, url);
        }

        @Override
        protected Response doCall(Request request) {
            if (exporter == null) {
                throw new JawsServiceException("InjvmReference call failed: no provider found, url=" + url.getUri());
            }

            return exporter.getProvider().call(request);
        }

        @Override
        protected boolean doInit() {
            String protocolKey = RpcUtils.getProtocolKey(url);

            // noinspection unchecked
            exporter = (Exporter<T>) exporterMap.get(protocolKey);

            if (exporter == null) {
                log.error("InjvmReference init failed: no provider found, url={}", url);
                return false;
            }

            return true;
        }

        @Override
        public void destroy() {
        }
    }
}