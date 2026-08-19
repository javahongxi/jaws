package org.hongxi.jaws.protocol;

import org.hongxi.jaws.common.extension.SpiMeta;
import org.hongxi.jaws.common.util.JawsFrameworkUtils;
import org.hongxi.jaws.exception.JawsErrorMsgConstants;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.rpc.*;
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
@SpiMeta(name = "injvm")
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
        public void unexport() {
            String protocolKey = JawsFrameworkUtils.getProtocolKey(url);

            // noinspection unchecked
            Exporter<T> exporter = (Exporter<T>) exporterMap.remove(protocolKey);

            if (exporter != null) {
                exporter.destroy();
            }

            log.info("InJvmExporter unexport Success: url={}", url);
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
                throw new JawsServiceException("InjvmReference call Error: provider not exist, url=" + url.getUri(),
                        JawsErrorMsgConstants.SERVICE_NOT_FOUND);
            }

            return exporter.getProvider().call(request);
        }

        @Override
        protected boolean doInit() {
            String protocolKey = JawsFrameworkUtils.getProtocolKey(url);

            // noinspection unchecked
            exporter = (Exporter<T>) exporterMap.get(protocolKey);

            if (exporter == null) {
                log.error("InjvmReference init Error: provider not exist, url={}", url);
                return false;
            }

            return true;
        }

        @Override
        public void destroy() {
        }
    }
}