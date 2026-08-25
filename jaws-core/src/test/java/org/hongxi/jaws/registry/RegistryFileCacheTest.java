package org.hongxi.jaws.registry;

import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.rpc.URL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the registry local file cache disaster recovery feature.
 * <p>
 * Verifies that discovered service URLs are persisted to a local file and
 * can be used as fallback when the registry center is unavailable.
 */
class RegistryFileCacheTest {

    @TempDir
    Path tempDir;

    private CacheTestRegistry registry;
    private File cacheFile;

    static class CacheTestRegistry extends FailbackRegistry {

        volatile boolean failAll;
        final Map<String, List<URL>> discoverResults = new HashMap<>();

        CacheTestRegistry(URL url) {
            super(url);
        }

        @Override
        protected void doRegister(URL url) {
            if (failAll) throw new RuntimeException("registry down");
        }

        @Override
        protected void doUnregister(URL url) {
            if (failAll) throw new RuntimeException("registry down");
        }

        @Override
        protected void doSubscribe(URL url, NotifyListener listener) {
            if (failAll) throw new RuntimeException("registry down");
        }

        @Override
        protected void doUnsubscribe(URL url, NotifyListener listener) {
            if (failAll) throw new RuntimeException("registry down");
        }

        @Override
        protected List<URL> doDiscover(URL url) {
            if (failAll) throw new RuntimeException("registry down");
            return discoverResults.getOrDefault(url.getPath(), List.of());
        }
    }

    @BeforeEach
    void setUp() {
        cacheFile = tempDir.resolve("test-registry.cache").toFile();

        Map<String, String> params = new HashMap<>();
        params.put(UrlParam.Registry.RETRY_PERIOD.getName(), "60000");
        params.put(UrlParam.Registry.LOCAL_FILE_CACHE_ENABLED.getName(), "true");
        params.put(UrlParam.Registry.CACHE_FILE.getName(), cacheFile.getAbsolutePath());
        params.put(UrlParam.Client.CHECK.getName(), "false");

        URL registryUrl = new URL("jaws", "127.0.0.1", 2181, "registry", params);
        registry = new CacheTestRegistry(registryUrl);
    }

    @AfterEach
    void tearDown() {
        if (cacheFile.exists()) {
            cacheFile.delete();
        }
        File lockFile = new File(cacheFile.getAbsolutePath() + ".lock");
        if (lockFile.exists()) {
            lockFile.delete();
        }
    }

    private URL consumerUrl(String path) {
        Map<String, String> params = new HashMap<>();
        params.put(UrlParam.Client.CHECK.getName(), "false");
        return new URL("jaws", "127.0.0.1", 0, path, params);
    }

    private List<URL> providerUrls(String path, int... ports) {
        List<URL> urls = new ArrayList<>();
        for (int port : ports) {
            Map<String, String> params = new HashMap<>();
            params.put(UrlParam.Identity.ENDPOINT_TYPE.getName(), "service");
            urls.add(new URL("jaws", "192.168.1.1", port, path, params));
        }
        return urls;
    }

    @Test
    void successfulDiscoverPersistsToFileCache() {
        URL consumer = consumerUrl("com.example.DemoService");
        List<URL> providers = providerUrls("com.example.DemoService", 20880, 20881);
        registry.discoverResults.put("com.example.DemoService", providers);

        List<URL> discovered = registry.discover(consumer);
        assertEquals(2, discovered.size());

        // Verify cache file was created
        assertTrue(cacheFile.exists(), "Cache file should be created after discover");
        assertTrue(cacheFile.length() > 0, "Cache file should not be empty");
    }

    @Test
    void discoverFallsBackToFileCacheWhenRegistryDown() {
        URL consumer = consumerUrl("com.example.DemoService");
        List<URL> providers = providerUrls("com.example.DemoService", 20880, 20881);
        registry.discoverResults.put("com.example.DemoService", providers);

        // First discover succeeds and caches to file
        List<URL> discovered = registry.discover(consumer);
        assertEquals(2, discovered.size());

        // Now simulate registry failure
        registry.failAll = true;

        // discover should fall back to file cache
        List<URL> fallback = registry.discover(consumer);
        assertNotNull(fallback);
        assertEquals(2, fallback.size());
    }

    @Test
    void subscribeFallsBackToFileCacheAndNotifiesListener() {
        URL consumer = consumerUrl("com.example.DemoService");
        List<URL> providers = providerUrls("com.example.DemoService", 20880, 20881);
        registry.discoverResults.put("com.example.DemoService", providers);

        // First discover succeeds and caches to file
        registry.discover(consumer);

        // Now simulate registry failure
        registry.failAll = true;

        // subscribe should fall back to cached URLs and notify the listener
        AtomicReference<List<URL>> notifiedUrls = new AtomicReference<>();
        NotifyListener listener = (registryUrl, urls) -> notifiedUrls.set(urls);

        registry.subscribe(consumer, listener);

        assertNotNull(notifiedUrls.get(), "Listener should be notified with cached URLs");
        assertEquals(2, notifiedUrls.get().size());
    }

    @Test
    void cacheFilePersistsAcrossRegistryRestart() {
        URL consumer = consumerUrl("com.example.DemoService");
        List<URL> providers = providerUrls("com.example.DemoService", 20880, 20881);
        registry.discoverResults.put("com.example.DemoService", providers);

        // First discover succeeds and caches to file
        registry.discover(consumer);
        assertTrue(cacheFile.exists());

        // Simulate a restart: create a new registry instance with the same cache file
        Map<String, String> params = new HashMap<>();
        params.put(UrlParam.Registry.RETRY_PERIOD.getName(), "60000");
        params.put(UrlParam.Registry.LOCAL_FILE_CACHE_ENABLED.getName(), "true");
        params.put(UrlParam.Registry.CACHE_FILE.getName(), cacheFile.getAbsolutePath());
        params.put(UrlParam.Client.CHECK.getName(), "false");

        URL registryUrl = new URL("jaws", "127.0.0.1", 2181, "registry", params);
        CacheTestRegistry newRegistry = new CacheTestRegistry(registryUrl);
        newRegistry.failAll = true;

        // The new registry should load the cache file and return cached URLs
        List<URL> fallback = newRegistry.discover(consumer);
        assertNotNull(fallback);
        assertEquals(2, fallback.size());
    }

    @Test
    void disabledFileCacheDoesNotCreateFile() {
        Map<String, String> params = new HashMap<>();
        params.put(UrlParam.Registry.RETRY_PERIOD.getName(), "60000");
        params.put(UrlParam.Registry.LOCAL_FILE_CACHE_ENABLED.getName(), "false");
        params.put(UrlParam.Client.CHECK.getName(), "false");

        URL registryUrl = new URL("jaws", "127.0.0.1", 2181, "registry", params);
        CacheTestRegistry noCacheRegistry = new CacheTestRegistry(registryUrl);

        URL consumer = consumerUrl("com.example.DemoService");
        List<URL> providers = providerUrls("com.example.DemoService", 20880);
        noCacheRegistry.discoverResults.put("com.example.DemoService", providers);

        noCacheRegistry.discover(consumer);

        // Cache file should NOT be created when disabled
        assertFalse(cacheFile.exists(), "Cache file should not be created when cache is disabled");
    }

    @Test
    void emptyDiscoverResultDoesNotCrash() {
        URL consumer = consumerUrl("com.example.EmptyService");
        // No discover results configured -> returns empty list

        List<URL> discovered = registry.discover(consumer);
        assertTrue(discovered.isEmpty());

        // Now fail and verify no crash
        registry.failAll = true;
        List<URL> fallback = registry.discover(consumer);
        assertTrue(fallback.isEmpty());
    }
}
