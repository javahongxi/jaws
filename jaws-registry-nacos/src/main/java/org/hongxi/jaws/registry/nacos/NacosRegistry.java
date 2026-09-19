package org.hongxi.jaws.registry.nacos;

import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.listener.NamingEvent;
import com.alibaba.nacos.api.naming.pojo.Instance;
import org.hongxi.jaws.common.lifecycle.Closeable;
import org.hongxi.jaws.common.lifecycle.ShutdownHook;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.registry.FailbackRegistry;
import org.hongxi.jaws.registry.NotifyListener;
import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Nacos-based registry implementation.
 * <p>
 * Maps Jaws registry operations to Nacos NamingService:
 * <ul>
 *   <li>Service registration: register Nacos instances with metadata containing URL parameters</li>
 *   <li>Service subscription: use Nacos subscribe to watch instance changes</li>
 *   <li>Service discovery: query all instances and convert back to URLs</li>
 * </ul>
 * <p>
 * Both session keepalive and reconnect-replay are delegated to
 * {@code nacos-client} (v3, gRPC), not implemented here:
 * <ul>
 *   <li><b>Keepalive</b>: instances are registered with
 *       {@code ephemeral=true}; under Nacos v2/v3 gRPC an ephemeral instance's
 *       liveness <em>is</em> the client's gRPC connection (server keys it by
 *       {@code connectionId}) — no application-level ClientBeat. The 1.x
 *       {@code BeatReactor} heartbeat path exists only in the legacy
 *       {@code NamingHttpClientProxy}.</li>
 *   <li><b>Reconnect &amp; rebuild</b>: nacos-client's
 *       {@code NamingGrpcRedoService} (a {@code ConnectionEventListener})
 *       replays pending register/subscribe on reconnect, with deregister
 *       taking precedence so a removed instance is never resurrected.
 *       This class intentionally does not hook a second reconnection path —
 *       see {@code doc/nacos-client-internals.md} §4–§5.</li>
 * </ul>
 * <p>
 * This is orthogonal to {@link FailbackRegistry}'s periodic {@code retry()},
 * which is an API-call failure queue active only under {@code check=false}
 * and does not observe connection events.
 * <p>
 * Created by shenhongxi on 2026/7/17.
 */
public class NacosRegistry extends FailbackRegistry implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(NacosRegistry.class);

    private static final String METADATA_KEY_PROTOCOL = "protocol";
    private static final String METADATA_KEY_PATH = "path";

    private final ReentrantLock clientLock = new ReentrantLock();
    private final ReentrantLock serverLock = new ReentrantLock();
    private final NamingService namingService;
    private final Map<URL, Map<NotifyListener, EventListener>> serviceListeners = new HashMap<>();

    public NacosRegistry(URL url, NamingService namingService) {
        super(url);
        this.namingService = namingService;
        ShutdownHook.registerShutdownHook(this);
    }

    @Override
    protected void doRegister(URL url) {
        try {
            serverLock.lock();
            String serviceName = NacosPathUtils.toServiceName(url);
            String group = NacosPathUtils.toGroup(url);
            Instance instance = new Instance();
            instance.setIp(url.getHost());
            instance.setPort(url.getPort());
            instance.setHealthy(true);
            instance.setEphemeral(true);
            // Store all URL parameters as metadata map, along with protocol and path
            Map<String, String> metadata = new HashMap<>(url.getParameters());
            metadata.put(METADATA_KEY_PROTOCOL, url.getProtocol());
            metadata.put(METADATA_KEY_PATH, url.getPath());
            instance.setMetadata(metadata);
            namingService.registerInstance(serviceName, group, instance);
        } catch (Throwable e) {
            throw new JawsFrameworkException(
                    String.format("Failed to register %s to nacos(%s), cause: %s", url, getUrl(), e.getMessage()), e);
        } finally {
            serverLock.unlock();
        }
    }

    @Override
    protected void doUnregister(URL url) {
        try {
            serverLock.lock();
            String serviceName = NacosPathUtils.toServiceName(url);
            String group = NacosPathUtils.toGroup(url);
            Instance instance = new Instance();
            instance.setIp(url.getHost());
            instance.setPort(url.getPort());
            namingService.deregisterInstance(serviceName, group, instance);
        } catch (Throwable e) {
            throw new JawsFrameworkException(
                    String.format("Failed to unregister %s from nacos(%s), cause: %s", url, getUrl(), e.getMessage()), e);
        } finally {
            serverLock.unlock();
        }
    }

    @Override
    protected void doSubscribe(URL url, NotifyListener listener) {
        try {
            clientLock.lock();
            String serviceName = NacosPathUtils.toServiceName(url);
            String group = NacosPathUtils.toGroup(url);
            Map<NotifyListener, EventListener> listeners = serviceListeners.computeIfAbsent(url, k -> new HashMap<>());
            EventListener eventListener = listeners.computeIfAbsent(listener, k -> event -> {
                if (event instanceof NamingEvent namingEvent) {
                    List<Instance> instances = namingEvent.getInstances();
                    List<URL> urls = instancesToUrls(url, instances);
                    listener.notify(getUrl(), urls);
                    log.info("service list change: serviceName={}, group={}, instanceCount={}",
                            serviceName, group, instances != null ? instances.size() : 0);
                }
            });
            namingService.subscribe(serviceName, group, eventListener);
            log.info("subscribe service: serviceName={}, group={}, info={}",
                    serviceName, group, url.toFullStr());
        } catch (Throwable e) {
            throw new JawsFrameworkException(
                    String.format("Failed to subscribe %s to nacos(%s), cause: %s", url, getUrl(), e.getMessage()), e);
        } finally {
            clientLock.unlock();
        }
    }

    @Override
    protected void doUnsubscribe(URL url, NotifyListener listener) {
        try {
            clientLock.lock();
            Map<NotifyListener, EventListener> listeners = serviceListeners.get(url);
            if (listeners != null) {
                EventListener eventListener = listeners.remove(listener);
                if (eventListener != null) {
                    String serviceName = NacosPathUtils.toServiceName(url);
                    String group = NacosPathUtils.toGroup(url);
                    namingService.unsubscribe(serviceName, group, eventListener);
                }
            }
        } catch (Throwable e) {
            throw new JawsFrameworkException(
                    String.format("Failed to unsubscribe %s from nacos(%s), cause: %s", url, getUrl(), e.getMessage()), e);
        } finally {
            clientLock.unlock();
        }
    }

    @Override
    protected List<URL> doDiscover(URL url) {
        try {
            String serviceName = NacosPathUtils.toServiceName(url);
            String group = NacosPathUtils.toGroup(url);
            List<Instance> instances = namingService.getAllInstances(serviceName, group);
            return instancesToUrls(url, instances);
        } catch (Throwable e) {
            throw new JawsFrameworkException(
                    String.format("Failed to discover service %s from nacos(%s), cause: %s", url, getUrl(), e.getMessage()), e);
        }
    }

    private List<URL> instancesToUrls(URL refUrl, List<Instance> instances) {
        List<URL> urls = new ArrayList<>();
        if (instances != null) {
            for (Instance instance : instances) {
                Map<String, String> metadata = instance.getMetadata();
                URL parsedUrl;
                if (metadata != null && metadata.containsKey(METADATA_KEY_PROTOCOL)) {
                    String protocol = metadata.get(METADATA_KEY_PROTOCOL);
                    String path = metadata.get(METADATA_KEY_PATH);
                    parsedUrl = new URL(protocol, instance.getIp(), instance.getPort(), path, new HashMap<>(metadata));
                } else {
                    // Fallback: reconstruct from reference URL
                    parsedUrl = refUrl.createCopy();
                    parsedUrl.setHost(instance.getIp());
                    parsedUrl.setPort(instance.getPort());
                }
                urls.add(parsedUrl);
            }
        }
        return urls;
    }

    @Override
    public void close() {
        try {
            namingService.shutDown();
        } catch (Exception e) {
            log.warn("failed to shutdown namingService", e);
        }
    }
}