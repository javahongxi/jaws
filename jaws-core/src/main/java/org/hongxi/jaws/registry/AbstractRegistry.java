package org.hongxi.jaws.registry;

import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <pre>
 * Abstract registry with local file cache support.
 *
 * Performs createCopy on all incoming URLs to prevent objects in the registry from being modified,
 * avoiding potential concurrency issues.
 *
 * When local file cache is enabled (default), discovered service URLs are persisted to a local
 * file so that consumers can still find providers when the registry center is unavailable.
 *
 * </pre>
 * <p>
 * Created by shenhongxi on 2021/4/21.
 */
public abstract class AbstractRegistry implements Registry {

    private static final Logger log = LoggerFactory.getLogger(AbstractRegistry.class);

    protected String registryClassName = this.getClass().getSimpleName();

    private final URL registryUrl;

    protected final Set<URL> registered = ConcurrentHashMap.newKeySet();

    protected final Map<URL, Set<NotifyListener>> subscribed = new ConcurrentHashMap<>();

    // ---- Local file cache for disaster recovery ----

    private final boolean localCacheEnabled;
    private File cacheFile;
    private volatile boolean cacheLoaded;

    /** In-memory mirror of the file cache: serviceKey -> space-separated URL strings. */
    private final Map<String, String> fileCacheData = new ConcurrentHashMap<>();

    public AbstractRegistry(URL url) {
        this.registryUrl = url.createCopy();
        this.localCacheEnabled = url.getBoolParameter(UrlParam.Registry.LOCAL_FILE_CACHE_ENABLED);
        if (localCacheEnabled) {
            initCacheFile(url);
        }
    }

    private void initCacheFile(URL url) {
        String configuredPath = url.getParameter(UrlParam.Registry.CACHE_FILE);
        String filename;
        if (configuredPath != null && !configuredPath.isEmpty()) {
            filename = configuredPath;
        } else {
            String userHome = System.getProperty("user.home");
            String app = url.getApplication();
            String address = url.getBackupAddress().replace(":", "-");
            filename = userHome + File.separator + ".jaws" + File.separator
                    + "registry" + File.separator + app + "-" + address + ".cache";
        }
        this.cacheFile = new File(filename);
        if (!cacheFile.exists() && cacheFile.getParentFile() != null) {
            cacheFile.getParentFile().mkdirs();
        }
    }

    @Override
    public void register(URL url) {
        log.info("[{}] Url ({}) will register to Registry [{}]", registryClassName, url, registryUrl.getIdentity());
        registered.add(url);
        doRegister(removeRegistryUnnecessaryParams(url.createCopy()));
    }

    @Override
    public void unregister(URL url) {
        log.info("[{}] Url ({}) will unregister to Registry [{}]", registryClassName, url, registryUrl.getIdentity());
        doUnregister(removeRegistryUnnecessaryParams(url.createCopy()));
        registered.remove(url);
    }

    @Override
    public void subscribe(URL url, NotifyListener listener) {
        log.info("[{}] Listener ({}) will subscribe to url ({}) in Registry [{}]",
                registryClassName, listener, url, registryUrl.getIdentity());
        subscribed.computeIfAbsent(url, k -> ConcurrentHashMap.newKeySet()).add(listener);
        doSubscribe(url.createCopy(), listener);
    }

    @Override
    public void unsubscribe(URL url, NotifyListener listener) {
        log.info("[{}] Listener ({}) will unsubscribe from url ({}) in Registry [{}]",
                registryClassName, listener, url, registryUrl.getIdentity());
        doUnsubscribe(url.createCopy(), listener);
        Set<NotifyListener> listeners = subscribed.get(url);
        if (listeners != null) {
            listeners.remove(listener);
            if (listeners.isEmpty()) {
                subscribed.remove(url);
            }
        }
    }

    @Override
    public List<URL> discover(URL url) {
        List<URL> results = new ArrayList<>();
        List<URL> urls = doDiscover(url.createCopy());
        if (urls != null) {
            for (URL u : urls) {
                results.add(u.createCopy());
            }
        }
        return results;
    }

    @Override
    public URL getUrl() {
        return registryUrl;
    }

    /**
     * Remove parameters that are irrelevant to service discovery before interacting with the registry.
     * <p>
     * These fall into two categories:
     * <ul>
     *   <li>Provider-local settings (e.g. thread pool, server connections, codec, transportFactory)
     *       that are transport/server concerns and not needed by consumers.</li>
     *   <li>Consumer-local settings (e.g. retries, loadBalance, retryPolicy, check)
     *       that each consumer configures independently and should not be inherited from the provider.</li>
     * </ul>
     */
    private URL removeRegistryUnnecessaryParams(URL url) {
        // Transport SPI: codec is a local transport concern, consumer applies default on connect
        url.getParameters().remove(UrlParam.Transport.CODEC.getName());
        url.getParameters().remove(UrlParam.Transport.TRANSPORT_FACTORY.getName());

        // Provider-local server settings
        url.getParameters().remove(UrlParam.Server.MAX_CONNECTIONS.getName());
        url.getParameters().remove(UrlParam.Server.MIN_WORKER_THREADS.getName());
        url.getParameters().remove(UrlParam.Server.MAX_WORKER_THREADS.getName());
        url.getParameters().remove(UrlParam.Server.WORKER_QUEUE_SIZE.getName());
        url.getParameters().remove(UrlParam.Transport.MAX_CONTENT_LENGTH.getName());
        url.getParameters().remove(UrlParam.Server.ACCESS_LOG.getName());
        // NOTE: heartbeat is intentionally kept — it is a shared protocol-level parameter
        // that both provider and consumer need for connection keep-alive.

        // Consumer-local settings: each consumer configures these independently
        url.getParameters().remove(UrlParam.Cluster.RETRIES.getName());
        url.getParameters().remove(UrlParam.Client.CHECK.getName());
        url.getParameters().remove(UrlParam.Client.THROW_EXCEPTION.getName());
        url.getParameters().remove(UrlParam.Cluster.LOAD_BALANCE.getName());
        url.getParameters().remove(UrlParam.Cluster.RETRY_POLICY.getName());
        url.getParameters().remove(UrlParam.Transport.REQUEST_TIMEOUT.getName());
        url.getParameters().remove(UrlParam.Transport.CONNECT_TIMEOUT.getName());
        url.getParameters().remove(UrlParam.Transport.FILTER.getName());
        url.getParameters().remove(UrlParam.Client.FUSING_THRESHOLD.getName());

        return url;
    }

    public Set<URL> getRegistered() {
        return Collections.unmodifiableSet(registered);
    }

    public Map<URL, Set<NotifyListener>> getSubscribed() {
        return Collections.unmodifiableMap(subscribed);
    }

    public File getCacheFile() {
        return cacheFile;
    }

    // ---- Local file cache operations ----

    /**
     * Build a service-level cache key from the consumer URL.
     * <p>
     * The key is composed of path, group and version so that all consumers
     * of the same service share one cache entry regardless of their individual
     * host/port or endpointType.
     */
    protected String buildServiceKey(URL url) {
        return url.getPath() + "/" + url.getGroup() + "/" + url.getVersion();
    }

    /**
     * Look up cached service URLs from both the in-memory discovery cache
     * and the local file cache. Returns null if nothing is cached.
     */
    protected List<URL> getCacheUrls(URL url) {
        if (!localCacheEnabled) {
            return null;
        }
        ensureCacheLoaded();
        String key = buildServiceKey(url);
        String value = fileCacheData.get(key);
        if (value != null && !value.isEmpty()) {
            List<URL> urls = new ArrayList<>();
            for (String part : value.split(" ")) {
                if (!part.isEmpty()) {
                    try {
                        urls.add(URL.valueOf(part));
                    } catch (Exception e) {
                        log.warn("Failed to parse cached url: {}", part, e);
                    }
                }
            }
            if (!urls.isEmpty()) {
                return urls;
            }
        }
        return null;
    }

    /**
     * Persist discovered service URLs to the local cache file.
     */
    protected void saveCacheUrls(URL url, List<URL> urls) {
        if (!localCacheEnabled || cacheFile == null) {
            return;
        }
        String key = buildServiceKey(url);
        if (urls == null || urls.isEmpty()) {
            fileCacheData.remove(key);
        } else {
            StringBuilder sb = new StringBuilder();
            for (URL u : urls) {
                if (!sb.isEmpty()) {
                    sb.append(' ');
                }
                sb.append(u.toFullStr());
            }
            fileCacheData.put(key, sb.toString());
        }
        doSaveCacheToFile();
    }

    private void ensureCacheLoaded() {
        if (!cacheLoaded) {
            synchronized (this) {
                if (!cacheLoaded) {
                    loadCacheFromFile();
                    cacheLoaded = true;
                }
            }
        }
    }

    private void loadCacheFromFile() {
        if (cacheFile == null || !cacheFile.exists()) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(cacheFile.toPath())) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                int tabIdx = line.indexOf('\t');
                if (tabIdx > 0 && tabIdx < line.length() - 1) {
                    String key = line.substring(0, tabIdx);
                    String value = line.substring(tabIdx + 1);
                    fileCacheData.put(key, value);
                }
            }
            log.info("Loaded registry cache file {}, entries: {}", cacheFile, fileCacheData.size());
        } catch (IOException e) {
            log.warn("Failed to load registry cache file {}", cacheFile, e);
        }
    }

    private synchronized void doSaveCacheToFile() {
        if (cacheFile == null) {
            return;
        }
        File lockFile = new File(cacheFile.getAbsolutePath() + ".lock");
        try {
            if (!lockFile.exists()) {
                lockFile.createNewFile();
            }
            try (BufferedWriter writer = Files.newBufferedWriter(cacheFile.toPath())) {
                for (Map.Entry<String, String> entry : fileCacheData.entrySet()) {
                    writer.write(entry.getKey());
                    writer.write('\t');
                    writer.write(entry.getValue());
                    writer.newLine();
                }
            }
        } catch (IOException e) {
            log.warn("Failed to save registry cache file {}", cacheFile, e);
        } finally {
            if (lockFile.exists() && !lockFile.delete()) {
                log.warn("Failed to delete cache lock file {}", lockFile);
            }
        }
    }

    protected abstract void doRegister(URL url);

    protected abstract void doUnregister(URL url);

    protected abstract void doSubscribe(URL url, NotifyListener listener);

    protected abstract void doUnsubscribe(URL url, NotifyListener listener);

    protected abstract List<URL> doDiscover(URL url);
}