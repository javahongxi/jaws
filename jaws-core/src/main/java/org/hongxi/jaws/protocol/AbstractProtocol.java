package org.hongxi.jaws.protocol;

import org.hongxi.jaws.common.util.RpcUtils;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.rpc.Endpoint;
import org.hongxi.jaws.rpc.Exporter;
import org.hongxi.jaws.rpc.Protocol;
import org.hongxi.jaws.rpc.Provider;
import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Base implementation of {@link Protocol} that manages exporter lifecycle with a shared exporter map.
 */
public abstract class AbstractProtocol implements Protocol {

    private static final Logger log = LoggerFactory.getLogger(AbstractProtocol.class);

    protected final ConcurrentMap<String, Exporter<?>> exporterMap = new ConcurrentHashMap<>();

    @Override
    public <T> Exporter<T> export(Provider<T> provider) {
        if (provider == null) {
            throw new JawsFrameworkException(this.getClass().getSimpleName() + " export failed: provider must not be null");
        }

        URL url = provider.getUrl();
        if (url == null) {
            throw new JawsFrameworkException(this.getClass().getSimpleName() + " export failed: provider url must not be null");
        }

        String protocolKey = RpcUtils.getProtocolKey(url);

        synchronized (exporterMap) {
            // noinspection unchecked
            Exporter<T> exporter = (Exporter<T>) exporterMap.get(protocolKey);
            if (exporter != null) {
                throw new JawsFrameworkException(this.getClass().getSimpleName() + " export failed: service already exists, url=" + url);
            }

            exporter = createExporter(provider);
            exporter.init();
            exporterMap.put(protocolKey, exporter);
            log.info("{} exported successfully: url={}", this.getClass().getSimpleName(), url);
            return exporter;
        }
    }

    @Override
    public <T> Reference<T> refer(Class<T> interfaceClass, URL url) {
        if (url == null) {
            throw new JawsFrameworkException(this.getClass().getSimpleName() + " refer failed: url must not be null");
        }

        if (interfaceClass == null) {
            throw new JawsFrameworkException(this.getClass().getSimpleName() + " refer failed: interfaceClass must not be null, url=" + url);
        }
        long start = System.currentTimeMillis();
        Reference<T> reference = createReference(interfaceClass, url);
        reference.init();

        log.info("{} referred successfully: url={}, cost:{}ms", this.getClass().getSimpleName(), url, System.currentTimeMillis() - start);

        return reference;
    }

    @Override
    public void unexport(URL url) {
        String protocolKey = RpcUtils.getProtocolKey(url);
        Exporter<?> exporter = exporterMap.remove(protocolKey);
        if (exporter != null) {
            exporter.destroy();
        }
    }

    protected abstract <T> Exporter<T> createExporter(Provider<T> provider);

    protected abstract <T> Reference<T> createReference(Class<T> interfaceClass, URL url);

    @Override
    public void destroy() {
        for (Map.Entry<String, Exporter<?>> entry : exporterMap.entrySet()) {
            Endpoint node = entry.getValue();
            if (node != null) {
                try {
                    node.destroy();
                    log.info("{} destroyed endpoint successfully: {}", this.getClass().getSimpleName(), node);
                } catch (Throwable t) {
                    log.error("{} failed to destroy endpoint", this.getClass().getSimpleName(), t);
                }
            }
        }
        exporterMap.clear();
    }
}