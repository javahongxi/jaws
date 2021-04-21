package org.hongxi.jaws.protocol;

import org.hongxi.jaws.common.util.JawsFrameworkUtils;
import org.hongxi.jaws.exception.JawsErrorMsgConstants;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.rpc.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Created by shenhongxi on 2021/4/21.
 */
public abstract class AbstractProtocol implements Protocol {

    private static final Logger log = LoggerFactory.getLogger(AbstractProtocol.class);

    protected ConcurrentHashMap<String, Exporter<?>> exporterMap = new ConcurrentHashMap<String, Exporter<?>>();

    public Map<String, Exporter<?>> getExporterMap() {
        return Collections.unmodifiableMap(exporterMap);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> Exporter<T> export(Provider<T> provider, URL url) {
        if (url == null) {
            throw new JawsFrameworkException(this.getClass().getSimpleName() + " export Error: url is null",
                    JawsErrorMsgConstants.FRAMEWORK_INIT_ERROR);
        }

        if (provider == null) {
            throw new JawsFrameworkException(this.getClass().getSimpleName() + " export Error: provider is null, url=" + url,
                    JawsErrorMsgConstants.FRAMEWORK_INIT_ERROR);
        }

        String protocolKey = JawsFrameworkUtils.getProtocolKey(url);

        synchronized (exporterMap) {
            Exporter<T> exporter = (Exporter<T>) exporterMap.get(protocolKey);

            if (exporter != null) {
                throw new JawsFrameworkException(this.getClass().getSimpleName() + " export Error: service already exist, url=" + url,
                        JawsErrorMsgConstants.FRAMEWORK_INIT_ERROR);
            }

            exporter = createExporter(provider, url);
            exporter.init();

            protocolKey =  JawsFrameworkUtils.getProtocolKey(url);// rebuild protocolKey，maybe port change when using random port
            exporterMap.put(protocolKey, exporter);

            log.info("{} export Success: url={}", this.getClass().getSimpleName(), url);

            return exporter;
        }


    }

    public <T> Referer<T> refer(Class<T> clz, URL url) {
        return refer(clz, url, url);
    }

    @Override
    public <T> Referer<T> refer(Class<T> clz, URL url, URL serviceUrl) {
        if (url == null) {
            throw new JawsFrameworkException(this.getClass().getSimpleName() + " refer Error: url is null",
                    JawsErrorMsgConstants.FRAMEWORK_INIT_ERROR);
        }

        if (clz == null) {
            throw new JawsFrameworkException(this.getClass().getSimpleName() + " refer Error: class is null, url=" + url,
                    JawsErrorMsgConstants.FRAMEWORK_INIT_ERROR);
        }
        long start = System.currentTimeMillis();
        Referer<T> referer = createReferer(clz, url, serviceUrl);
        referer.init();

        log.info("{} refer Success: url={}, cost:{}", this.getClass().getSimpleName(), url, System.currentTimeMillis() - start);

        return referer;
    }

    protected abstract <T> Exporter<T> createExporter(Provider<T> provider, URL url);

    protected abstract <T> Referer<T> createReferer(Class<T> clz, URL url, URL serviceUrl);

    @Override
    public void destroy() {
        for (String key : exporterMap.keySet()) {
            Node node = exporterMap.remove(key);

            if (node != null) {
                try {
                    node.destroy();

                    log.info("{} destroy node Success: {}", this.getClass().getSimpleName(), node);
                } catch (Throwable t) {
                    log.error("{} destroy Error", this.getClass().getSimpleName(), t);
                }
            }
        }
    }
}
