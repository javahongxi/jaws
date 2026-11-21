package org.hongxi.jaws.cluster.directory;

import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.common.extension.ExtensionLoader;
import org.hongxi.jaws.common.lifecycle.Closeable;
import org.hongxi.jaws.common.util.ReflectUtils;
import org.hongxi.jaws.metadata.MetadataCache;
import org.hongxi.jaws.metadata.MetadataInfo;
import org.hongxi.jaws.metadata.MetadataService;
import org.hongxi.jaws.registry.Registry;
import org.hongxi.jaws.registry.RegistryFactory;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.Protocol;
import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Application-level service discovery directory (local metadata mode), the
 * minimal counterpart of Dubbo's {@code ServiceInstancesChangedListener} plus
 * {@code MetadataUtils} pull chain.
 * <p>
 * It subscribes to one lightweight instance per application (path = application
 * name, parameters carry a {@code revision} pointer) instead of per-interface
 * URLs. On each notification instances are grouped by revision; every group is
 * resolved through a short-circuit chain — shared {@link MetadataCache} first,
 * then a point-to-point {@link MetadataService} RPC against a randomly picked
 * instance of the group — and the fetched metadata is expanded back into
 * interface-level URLs which are handed to {@link RegistryDirectory#notify} so
 * all refer/dedup/recycle logic below stays untouched.
 * <p>
 * When a revision cannot be fetched from any instance, the notification is
 * voided (current addresses kept) and a background task retries every 10 seconds
 * until it succeeds, mirroring Dubbo's {@code AddressRefreshRetryTask}.
 *
 * @param <T> service type
 */
public class AppLevelRegistryDirectory<T> extends RegistryDirectory<T> {

    private static final Logger log = LoggerFactory.getLogger(AppLevelRegistryDirectory.class);

    private static final long RETRY_INTERVAL_SECONDS = 10;

    private final String application;

    /** The consumer reference URL, same object the parent holds privately. */
    private final URL refUrl;

    /** The protocol used to refer, same object the parent holds privately. */
    private final Protocol protocol;

    private final List<URL> registryUrls;

    /** Subscription URL: the consumer URL with path replaced by the application name. */
    private final URL appSubscribeUrl;

    /** Latest instance snapshot per registry, replayed by the retry task. */
    private final ConcurrentMap<URL, List<URL>> lastInstances = new ConcurrentHashMap<>();

    private final ScheduledExecutorService retryExecutor;
    private final AtomicBoolean retrying = new AtomicBoolean(false);
    private volatile ScheduledFuture<?> retryFuture;
    private volatile boolean destroyed;

    public AppLevelRegistryDirectory(Class<T> interfaceClass, URL url, URL consumerUrl,
                                     List<URL> registryUrls, Protocol protocol, String application) {
        super(interfaceClass, url, consumerUrl, registryUrls, protocol);
        this.application = application;
        this.refUrl = url;
        this.protocol = protocol;
        this.registryUrls = List.copyOf(registryUrls);
        this.appSubscribeUrl = consumerUrl.createCopy();
        this.appSubscribeUrl.setPath(application);
        this.retryExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "jaws-app-level-retry-" + application);
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void init() {
        for (URL registryUrl : registryUrls) {
            Registry registry = getRegistry(registryUrl);
            registry.subscribe(appSubscribeUrl, this);

            // Same race guard as the parent: the subscribe notification is
            // asynchronous, so pull the current instance list synchronously too.
            List<URL> discovered = registry.discover(appSubscribeUrl);
            log.info("app-level init discover: application={}, registry={}, instanceCount={}",
                    application, registryUrl.getUri(), discovered == null ? 0 : discovered.size());
            if (discovered != null && !discovered.isEmpty()) {
                notify(registryUrl, discovered);
            }
        }
    }

    /**
     * Incoming urls are application instances, not service URLs: group them by
     * revision, resolve each group to metadata, expand to the interface-level
     * URLs this reference cares about, then delegate to the parent.
     */
    @Override
    public synchronized void notify(URL registryUrl, List<URL> urls) {
        if (urls == null || urls.isEmpty()) {
            // Empty protection stays with the parent (keeps stale references).
            lastInstances.remove(registryUrl);
            super.notify(registryUrl, urls);
            return;
        }

        lastInstances.put(registryUrl, List.copyOf(urls));
        List<URL> serviceUrls = expand(urls);
        if (serviceUrls == null) {
            log.warn("Metadata fetch failed for application={}, keep current addresses and retry in {}s",
                    application, RETRY_INTERVAL_SECONDS);
            scheduleRetry();
            return;
        }
        super.notify(registryUrl, serviceUrls);
    }

    @Override
    public void destroy() {
        destroyed = true;
        ScheduledFuture<?> future = retryFuture;
        if (future != null) {
            future.cancel(false);
        }
        retryExecutor.shutdownNow();
        for (URL registryUrl : registryUrls) {
            try {
                Registry registry = getRegistry(registryUrl);
                registry.unsubscribe(appSubscribeUrl, this);
                if (registry instanceof Closeable) {
                    ((Closeable) registry).close();
                }
            } catch (Exception e) {
                log.warn("Failed to destroy registry for application={}, registry={}",
                        application, registryUrl.getUri(), e);
            }
        }
    }

    /**
     * Group instances by revision and expand each group's metadata into the
     * service URLs matching this reference.
     *
     * @return the expanded URLs, or null when any revision failed to resolve
     *         (the whole notification is then voided and retried)
     */
    private List<URL> expand(List<URL> instances) {
        Map<String, List<URL>> revisionToInstances = new LinkedHashMap<>();
        for (URL instance : instances) {
            String revision = instance.getParameter(UrlParam.Identity.REVISION.getName());
            if (revision == null || revision.isEmpty()) {
                log.warn("Application instance without revision parameter, skipped: {}", instance.toSimpleString());
                continue;
            }
            revisionToInstances.computeIfAbsent(revision, k -> new ArrayList<>()).add(instance);
        }

        // Dedup by service identity across revisions: during a rolling update two
        // revisions may both describe this interface, one reference is enough.
        Map<String, URL> serviceUrls = new LinkedHashMap<>();
        for (Map.Entry<String, List<URL>> entry : revisionToInstances.entrySet()) {
            String revision = entry.getKey();
            MetadataInfo metadata = MetadataCache.get(revision);
            if (metadata == null) {
                metadata = fetchMetadata(revision, entry.getValue());
                if (metadata == null) {
                    return null;
                }
                MetadataCache.put(revision, metadata);
            } else {
                log.info("Metadata cache hit: application={}, revision={}, serviceCount={}",
                        application, revision, metadata.getServices().size());
            }
            for (String fullStr : metadata.getServices().values()) {
                URL serviceUrl = URL.valueOf(fullStr);
                if (serviceUrl.canServe(refUrl)) {
                    serviceUrls.putIfAbsent(serviceUrl.getIdentity(), serviceUrl);
                }
            }
        }
        return new ArrayList<>(serviceUrls.values());
    }

    /**
     * Pull metadata for one revision over a throw-away {@link MetadataService}
     * reference. Instances are tried in random order to avoid hotspotting one
     * provider; the reference reuses the instance's business port and transport
     * parameters (serialization included) and is always destroyed afterwards.
     * Protected so tests can inject metadata without a real transport.
     *
     * @return validated metadata, or null when every instance failed
     */
    protected MetadataInfo fetchMetadata(String revision, List<URL> instances) {
        List<URL> candidates = new ArrayList<>(instances);
        Collections.shuffle(candidates);
        for (URL instance : candidates) {
            Reference<MetadataService> reference = null;
            try {
                URL metadataUrl = instance.createCopy();
                metadataUrl.setPath(MetadataService.class.getName());
                metadataUrl.removeParameter(UrlParam.Identity.REVISION.getName());
                reference = protocol.refer(MetadataService.class, metadataUrl);

                DefaultRequest request = new DefaultRequest();
                request.setInterfaceName(MetadataService.class.getName());
                request.setMethodName("getMetadataInfo");
                request.setParamDesc(ReflectUtils.getMethodParamDesc(
                        MetadataService.class.getMethod("getMetadataInfo", String.class)));
                request.setArguments(new Object[]{revision});
                // Provider-side dispatch keys on group/version attachments; copy
                // them from the instance so custom coordinates keep matching.
                request.setAttachment(UrlParam.Identity.GROUP.getName(), instance.getGroup());
                request.setAttachment(UrlParam.Identity.VERSION.getName(), instance.getVersion());

                Object value = reference.call(request).getValue();
                if (value instanceof MetadataInfo metadata
                        && revision.equals(metadata.getRevision())
                        && revision.equals(metadata.calRevision())) {
                    log.info("Fetched metadata from {}:{}, application={}, revision={}, serviceCount={}",
                            instance.getHost(), instance.getPort(), application, revision, metadata.getServices().size());
                    return metadata;
                }
                log.warn("Metadata missing or revision mismatch from {}:{}, requested revision={}",
                        instance.getHost(), instance.getPort(), revision);
            } catch (Exception e) {
                log.warn("Failed to fetch metadata from {}:{}, revision={}: {}",
                        instance.getHost(), instance.getPort(), revision, e.getMessage());
            } finally {
                if (reference != null) {
                    try {
                        reference.destroy();
                    } catch (Exception e) {
                        log.warn("Failed to destroy metadata reference to {}:{}",
                                instance.getHost(), instance.getPort(), e);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Replay the latest instance snapshots every 10 seconds until every registry
     * resolves; a single task per directory, guarded against re-entry.
     */
    private void scheduleRetry() {
        if (destroyed || !retrying.compareAndSet(false, true)) {
            return;
        }
        retryFuture = retryExecutor.scheduleWithFixedDelay(() -> {
            try {
                for (Map.Entry<URL, List<URL>> entry : lastInstances.entrySet()) {
                    List<URL> serviceUrls = expand(entry.getValue());
                    if (serviceUrls == null) {
                        // Still failing, keep the schedule for the next round.
                        return;
                    }
                    AppLevelRegistryDirectory.super.notify(entry.getKey(), serviceUrls);
                }
                if (retrying.compareAndSet(true, false)) {
                    ScheduledFuture<?> future = retryFuture;
                    if (future != null) {
                        future.cancel(false);
                    }
                }
            } catch (Exception e) {
                log.warn("Application-level address refresh retry failed, application={}", application, e);
            }
        }, RETRY_INTERVAL_SECONDS, RETRY_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private Registry getRegistry(URL url) {
        return ExtensionLoader.getExtensionLoader(RegistryFactory.class)
                .getExtension(url.getProtocol())
                .getRegistry(url);
    }
}