package org.hongxi.jaws.registry.harbor;

import org.hongxi.jaws.common.lifecycle.Closeable;
import org.hongxi.jaws.common.lifecycle.ShutdownHook;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.harbor.client.HarborClient;
import org.hongxi.jaws.harbor.model.Instance;
import org.hongxi.jaws.harbor.model.ServiceInfo;
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
import java.util.function.Consumer;

/**
 * Registry backed by Harbor's native client — the same wire protocol the server
 * answers for nacos-client, minus that SDK and its transitive tree.
 * <p>
 * The URL ↔ instance mapping is deliberately the same as {@code NacosRegistry}
 * (parameters plus {@code protocol}/{@code path} into instance metadata, rebuilt on
 * the read side), so switching a service from one leg to the other is a config
 * change rather than a data migration.
 * <p>
 * Disaster tolerance stays where it already is: {@code AbstractRegistry}'s local file
 * cache and {@code FailbackRegistry}'s retry sets sit under this class, and
 * {@code RegistryDirectory} keeps its existing references when a notification comes
 * back empty. A registry implementation that duplicated those would put three owners
 * on one decision.
 *
 * @author shenhongxi
 */
public class HarborRegistry extends FailbackRegistry implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(HarborRegistry.class);

    private static final String METADATA_KEY_PROTOCOL = "protocol";
    private static final String METADATA_KEY_PATH = "path";

    private final ReentrantLock clientLock = new ReentrantLock();
    private final ReentrantLock serverLock = new ReentrantLock();
    private final HarborClient client;

    /** NotifyListener → the exact Consumer handed to subscribe; listeners match by identity. */
    private final Map<URL, Map<NotifyListener, Consumer<ServiceInfo>>> serviceListeners = new HashMap<>();

    public HarborRegistry(URL url, HarborClient client) {
        super(url);
        this.client = client;
        ShutdownHook.registerShutdownHook(this);
    }

    @Override
    protected void doRegister(URL url) {
        try {
            serverLock.lock();
            String serviceName = HarborPathUtils.toServiceName(url);
            String group = HarborPathUtils.toGroup(url);
            Instance instance = new Instance();
            instance.setIp(url.getHost());
            instance.setPort(url.getPort());
            instance.setHealthy(true);
            instance.setEphemeral(true);
            Map<String, String> metadata = new HashMap<>(url.getParameters());
            metadata.put(METADATA_KEY_PROTOCOL, url.getProtocol());
            metadata.put(METADATA_KEY_PATH, url.getPath());
            instance.setMetadata(metadata);
            client.registerInstance(serviceName, group, instance);
        } catch (Throwable e) {
            throw new JawsFrameworkException(String.format("Failed to register %s to harbor(%s), cause: %s",
                    url, getUrl(), e.getMessage()), e);
        } finally {
            serverLock.unlock();
        }
    }

    @Override
    protected void doUnregister(URL url) {
        try {
            serverLock.lock();
            String serviceName = HarborPathUtils.toServiceName(url);
            String group = HarborPathUtils.toGroup(url);
            Instance instance = new Instance();
            instance.setIp(url.getHost());
            instance.setPort(url.getPort());
            client.deregisterInstance(serviceName, group, instance);
        } catch (Throwable e) {
            throw new JawsFrameworkException(String.format("Failed to unregister %s from harbor(%s), cause: %s",
                    url, getUrl(), e.getMessage()), e);
        } finally {
            serverLock.unlock();
        }
    }

    @Override
    protected void doSubscribe(URL url, NotifyListener listener) {
        try {
            clientLock.lock();
            String serviceName = HarborPathUtils.toServiceName(url);
            String group = HarborPathUtils.toGroup(url);
            Map<NotifyListener, Consumer<ServiceInfo>> listeners =
                    serviceListeners.computeIfAbsent(url, k -> new HashMap<>());
            Consumer<ServiceInfo> consumer = listeners.computeIfAbsent(listener, k -> serviceInfo -> {
                List<URL> urls = instancesToUrls(url, serviceInfo);
                listener.notify(getUrl(), urls);
                log.info("service list change: serviceName={}, group={}, instanceCount={}",
                        serviceName, group, urls.size());
            });
            client.subscribe(serviceName, group, consumer);
            log.info("subscribe service: serviceName={}, group={}, info={}",
                    serviceName, group, url.toFullStr());
        } catch (Throwable e) {
            throw new JawsFrameworkException(String.format("Failed to subscribe %s to harbor(%s), cause: %s",
                    url, getUrl(), e.getMessage()), e);
        } finally {
            clientLock.unlock();
        }
    }

    @Override
    protected void doUnsubscribe(URL url, NotifyListener listener) {
        try {
            clientLock.lock();
            Map<NotifyListener, Consumer<ServiceInfo>> listeners = serviceListeners.get(url);
            if (listeners != null) {
                Consumer<ServiceInfo> consumer = listeners.remove(listener);
                if (consumer != null) {
                    client.unsubscribe(HarborPathUtils.toServiceName(url),
                            HarborPathUtils.toGroup(url), consumer);
                }
            }
        } catch (Throwable e) {
            throw new JawsFrameworkException(String.format("Failed to unsubscribe %s from harbor(%s), cause: %s",
                    url, getUrl(), e.getMessage()), e);
        } finally {
            clientLock.unlock();
        }
    }

    @Override
    protected List<URL> doDiscover(URL url) {
        try {
            String serviceName = HarborPathUtils.toServiceName(url);
            String group = HarborPathUtils.toGroup(url);
            // healthyOnly=false: like the nacos leg, this reports what the registry
            // knows and lets the consumer's own filters decide, instead of hiding an
            // instance behind a health verdict made from connection silence.
            return instancesToUrls(url, serviceName, group,
                    client.getInstances(serviceName, group, false));
        } catch (Throwable e) {
            throw new JawsFrameworkException(String.format("Failed to discover service %s from harbor(%s), cause: %s",
                    url, getUrl(), e.getMessage()), e);
        }
    }

    private List<URL> instancesToUrls(URL refUrl, ServiceInfo serviceInfo) {
        return instancesToUrls(refUrl, serviceInfo.getName(), serviceInfo.getGroupName(),
                serviceInfo.getHosts());
    }

    private List<URL> instancesToUrls(URL refUrl, String serviceName, String group,
                                      List<Instance> instances) {
        List<URL> urls = new ArrayList<>();
        if (instances == null) {
            return urls;
        }
        for (Instance instance : instances) {
            Map<String, String> metadata = instance.getMetadata();
            URL parsedUrl;
            if (metadata != null && metadata.containsKey(METADATA_KEY_PROTOCOL)) {
                String protocol = metadata.get(METADATA_KEY_PROTOCOL);
                String path = metadata.get(METADATA_KEY_PATH);
                parsedUrl = new URL(protocol, instance.getIp(), instance.getPort(), path,
                        new HashMap<>(metadata));
            } else {
                // Foreign instance (registered by another client, no jaws metadata):
                // rebuild it as a consumer URL and fill in the service coordinates,
                // rather than silently dropping a provider we can see but cannot type.
                log.info("harbor instance {}:{} of {}/{} carries no jaws metadata,"
                                + " rebuilding it as a consumer url",
                        instance.getIp(), instance.getPort(), group, serviceName);
                parsedUrl = refUrl.createCopy();
                parsedUrl.setHost(instance.getIp());
                parsedUrl.setPort(instance.getPort());
            }
            urls.add(parsedUrl);
        }
        return urls;
    }

    @Override
    public void close() {
        try {
            client.close();
        } catch (Exception e) {
            log.warn("failed to close harbor client", e);
        }
    }
}
