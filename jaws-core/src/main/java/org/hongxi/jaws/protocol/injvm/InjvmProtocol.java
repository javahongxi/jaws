package org.hongxi.jaws.protocol.injvm;

import org.hongxi.jaws.common.extension.SpiMeta;
import org.hongxi.jaws.common.util.JawsFrameworkUtils;
import org.hongxi.jaws.exception.JawsErrorMsgConstants;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.protocol.AbstractProtocol;
import org.hongxi.jaws.rpc.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JVM 节点内部的调用
 *
 * <pre>
 * 		1) provider 和 reference 相对应
 * 		2) provider 需要在被consumer refer 之前需要 export
 * </pre>
 * <p>
 * Created by shenhongxi on 2021/4/21.
 *
 */
@SpiMeta(name = "injvm")
public class InjvmProtocol extends AbstractProtocol {

    private static final Logger log = LoggerFactory.getLogger(InjvmProtocol.class);

    @Override
    protected <T> Exporter<T> createExporter(Provider<T> provider, URL url) {
        return new InJvmExporter<T>(provider, url);
    }

    @Override
    protected <T> Reference<T> createReference(Class<T> clazz, URL url, URL serviceUrl) {
        return new InjvmReference<T>(clazz, url, serviceUrl);
    }

    /**
     * injvm provider
     *
     * @param <T>
     */
    class InJvmExporter<T> extends AbstractExporter<T> {
        public InJvmExporter(Provider<T> provider, URL url) {
            super(provider, url);
        }

        @Override
        @SuppressWarnings("unchecked")
        public void unexport() {
            String protocolKey = JawsFrameworkUtils.getProtocolKey(url);

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

        public InjvmReference(Class<T> clazz, URL url, URL serviceUrl) {
            super(clazz, url, serviceUrl);
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
        @SuppressWarnings("unchecked")
        protected boolean doInit() {
            String protocolKey = JawsFrameworkUtils.getProtocolKey(url);

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