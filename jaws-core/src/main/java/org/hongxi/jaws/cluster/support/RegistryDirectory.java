package org.hongxi.jaws.cluster.support;

import org.hongxi.jaws.common.extension.ExtensionLoader;
import org.hongxi.jaws.common.util.CollectionUtils;
import org.hongxi.jaws.registry.NotifyListener;
import org.hongxi.jaws.registry.Registry;
import org.hongxi.jaws.registry.RegistryFactory;
import org.hongxi.jaws.rpc.Protocol;
import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * A dynamic {@link org.hongxi.jaws.cluster.Directory} that subscribes to one or more
 * registries and keeps the reference list up-to-date as providers change.
 * <p>
 * This is the primary directory implementation for registry-based service discovery.
 *
 * @param <T> service type
 */
public class RegistryDirectory<T> extends AbstractDirectory<T> implements NotifyListener {

    private static final Logger log = LoggerFactory.getLogger(RegistryDirectory.class);

    private final Class<T> interfaceClass;

    /** The consumer reference URL, with nodeType=reference. */
    private final URL url;

    private final List<URL> registryUrls;

    private final Protocol protocol;

    /**
     * Active references grouped by their source registry.
     * <p>
     * Key: registry URL identifying which registry center the references were discovered from.
     * Value: list of {@link Reference} instances obtained from that registry.
     */
    private final ConcurrentMap<URL, List<Reference<T>>> registryReferences = new ConcurrentHashMap<>();

    public RegistryDirectory(Class<T> interfaceClass, URL url, URL consumerUrl,
                             List<URL> registryUrls, Protocol protocol) {
        super(consumerUrl);
        this.interfaceClass = interfaceClass;
        this.url = url;
        this.registryUrls = registryUrls;
        this.protocol = protocol;
    }

    @Override
    public void init() {
        for (URL registryUrl : registryUrls) {
            // Register self and subscribe to service list
            Registry registry = getRegistry(registryUrl);
            registry.subscribe(consumerUrl, this);

            // Immediately discover existing instances since the subscribe
            // notification is asynchronous and may not arrive before we check.
            List<URL> discovered = registry.discover(consumerUrl);
            log.info("[RegistryDirectory] init discover: consumerUrl={}, discoveredCount={}",
                    consumerUrl.toSimpleString(), discovered == null ? 0 : discovered.size());
            if (!CollectionUtils.isEmpty(discovered)) {
                log.info("[RegistryDirectory] discovered urls: {}", formatIdentities(discovered));
                notify(registryUrl, discovered);
            }
        }
    }

    @Override
    public void destroy() {
        for (URL registryUrl : registryUrls) {
            try {
                Registry registry = getRegistry(registryUrl);
                registry.unsubscribe(consumerUrl, this);
            } catch (Exception e) {
                log.warn("Failed to unsubscribe for url={}, registry={}", url, registryUrl.getIdentity(), e);
            }
        }
    }

    /**
     * <pre>
     * 1. notify must be executed serially (synchronized)
     * 2. notify is always full-volume; cluster must recycle unused references to avoid resource leaks
     * 3. If the registry has no remaining references and no other references exist, ignore the notification
     * </pre>
     */
    @Override
    public synchronized void notify(URL registryUrl, List<URL> urls) {
        if (CollectionUtils.isEmpty(urls)) {
            log.warn("No service urls from registry={}, service={}", registryUrl.getUri(), url.getIdentity());
            onRegistryEmpty(registryUrl);
            return;
        }

        log.info("Service urls changed: registry={}, service={}, urls={}",
                registryUrl.getUri(), url.getIdentity(), formatIdentities(urls));

        List<Reference<T>> newReferences = new ArrayList<>();
        for (URL serviceUrl : urls) {
            if (!serviceUrl.canServe(url)) {
                log.warn("[RegistryDirectory] discovered URL filtered by canServe: discovered={}, refUrl={}",
                        serviceUrl.toSimpleString(), url.toSimpleString());
                continue;
            }
            Reference<T> reference = findMatchingReference(serviceUrl, registryReferences.get(registryUrl));
            if (reference == null) {
                URL referenceURL = serviceUrl.createCopy();
                referenceURL.addParameters(this.url.getParameters());
                reference = protocol.refer(interfaceClass, referenceURL);
            }
            if (reference != null) {
                newReferences.add(reference);
            }
        }

        if (CollectionUtils.isEmpty(newReferences)) {
            onRegistryEmpty(registryUrl);
            return;
        }

        registryReferences.put(registryUrl, newReferences);
        refreshReferences();
    }

    private void onRegistryEmpty(URL emptyRegistryUrl) {
        if (registryReferences.size() > 1 || !registryReferences.containsKey(emptyRegistryUrl)) {
            // Other registries still have references, or this registry was already removed
            registryReferences.remove(emptyRegistryUrl);
            refreshReferences();
        } else {
            // Last registry became empty; warn but don't clear stale references
            log.warn("No more references in this cluster, registry={}, directory={}", emptyRegistryUrl, consumerUrl);
        }
    }

    private void refreshReferences() {
        List<Reference<T>> allReferences = new ArrayList<>();
        for (List<Reference<T>> refs : registryReferences.values()) {
            allReferences.addAll(refs);
        }
        setReferences(allReferences);
    }

    private Reference<T> findMatchingReference(URL url, List<Reference<T>> references) {
        if (references == null) {
            return null;
        }
        for (Reference<T> r : references) {
            if (Objects.equals(url, r.getUrl()) || Objects.equals(url, r.getServiceUrl())) {
                return r;
            }
        }
        return null;
    }

    private String formatIdentities(List<URL> urls) {
        if (urls == null || urls.isEmpty()) {
            return "[]";
        }
        StringJoiner sj = new StringJoiner(",", "[", "]");
        for (URL u : urls) {
            sj.add(u.getIdentity());
        }
        return sj.toString();
    }

    private Registry getRegistry(URL url) {
        return ExtensionLoader.getExtensionLoader(RegistryFactory.class)
                .getExtension(url.getProtocol())
                .getRegistry(url);
    }
}
