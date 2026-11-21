package org.hongxi.jaws.cluster.directory;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.metadata.MetadataCache;
import org.hongxi.jaws.metadata.MetadataInfo;
import org.hongxi.jaws.registry.RegistryService;
import org.hongxi.jaws.rpc.Exporter;
import org.hongxi.jaws.rpc.Protocol;
import org.hongxi.jaws.rpc.Provider;
import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.URL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Revision grouping, cache short-circuit and failure behaviour of
 * {@link AppLevelRegistryDirectory}. Metadata fetching is injected by overriding
 * {@code fetchMetadata}, and refer is stubbed, so no transport is involved.
 */
class AppLevelRegistryDirectoryTest {

    interface DemoService {
        String hello(String name);
    }

    private static final String APP = "test-app";
    private static final URL REGISTRY_URL =
            new URL("local", "127.0.0.1", 0, RegistryService.class.getName());

    private final List<AppLevelRegistryDirectory<DemoService>> directories = new ArrayList<>();

    @BeforeEach
    void setUp() {
        MetadataCache.clear();
    }

    @AfterEach
    void tearDown() {
        for (AppLevelRegistryDirectory<DemoService> directory : directories) {
            directory.destroy();
        }
        directories.clear();
    }

    // ---- fixtures ----

    private URL refUrl() {
        Map<String, String> params = new HashMap<>();
        params.put(UrlParam.Identity.ENDPOINT_TYPE.getName(), JawsConstants.ENDPOINT_TYPE_REFERENCE);
        return new URL("jaws", "127.0.0.1", 0, DemoService.class.getName(), params);
    }

    private URL instance(int port, String revision) {
        Map<String, String> params = new HashMap<>();
        params.put(UrlParam.Identity.ENDPOINT_TYPE.getName(), JawsConstants.ENDPOINT_TYPE_SERVICE);
        if (revision != null) {
            params.put(UrlParam.Identity.REVISION.getName(), revision);
        }
        return new URL("jaws", "10.0.0.1", port, APP, params);
    }

    private MetadataInfo metadata(int providerPort, String iface) {
        Map<String, String> params = new HashMap<>();
        params.put(UrlParam.Identity.ENDPOINT_TYPE.getName(), JawsConstants.ENDPOINT_TYPE_SERVICE);
        MetadataInfo info = new MetadataInfo(APP);
        info.addServiceUrl(new URL("jaws", "10.0.0.1", providerPort, iface, params));
        info.init();
        return info;
    }

    private TestableDirectory newDirectory() {
        URL refUrl = refUrl();
        URL consumerUrl = refUrl.createCopy();
        consumerUrl.addParameter(UrlParam.Identity.ENDPOINT_TYPE.getName(), JawsConstants.ENDPOINT_TYPE_SERVICE);
        TestableDirectory directory = new TestableDirectory(refUrl, consumerUrl);
        directories.add(directory);
        return directory;
    }

    // ---- tests ----

    @Test
    void sameRevisionGroupFetchesOnce() {
        TestableDirectory directory = newDirectory();
        MetadataInfo info = metadata(8080, DemoService.class.getName());
        directory.store.put(info.getRevision(), info);

        directory.notify(REGISTRY_URL, List.of(instance(8080, info.getRevision()), instance(8081, info.getRevision())));

        assertEquals(1, directory.fetchCount.get(), "one RPC per revision, not per instance");
        assertEquals(1, directory.getReferences().size());
    }

    @Test
    void secondNotifyHitsCacheWithoutRpc() {
        TestableDirectory directory = newDirectory();
        MetadataInfo info = metadata(8080, DemoService.class.getName());
        directory.store.put(info.getRevision(), info);
        List<URL> instances = List.of(instance(8080, info.getRevision()));

        directory.notify(REGISTRY_URL, instances);
        directory.notify(REGISTRY_URL, instances);

        assertEquals(1, directory.fetchCount.get(), "the shared revision cache short-circuits the second notify");
    }

    @Test
    void twoRevisionsExpandToTwoReferences() {
        TestableDirectory directory = newDirectory();
        MetadataInfo old = metadata(8080, DemoService.class.getName());
        MetadataInfo current = metadata(9090, DemoService.class.getName());
        directory.store.put(old.getRevision(), old);
        directory.store.put(current.getRevision(), current);

        directory.notify(REGISTRY_URL, List.of(instance(8080, old.getRevision()), instance(9090, current.getRevision())));

        assertEquals(2, directory.fetchCount.get());
        assertEquals(2, directory.getReferences().size(), "rolling update: both revisions serve addresses");
    }

    @Test
    void failedFetchVoidsNotificationAndKeepsAddresses() {
        TestableDirectory directory = newDirectory();
        MetadataInfo info = metadata(8080, DemoService.class.getName());
        directory.store.put(info.getRevision(), info);
        directory.notify(REGISTRY_URL, List.of(instance(8080, info.getRevision())));
        assertEquals(1, directory.getReferences().size());

        // A brand-new revision nobody can serve: notification must be voided.
        directory.failFetch = true;
        directory.notify(REGISTRY_URL, List.of(instance(8080, "unfetchable-revision")));

        assertEquals(1, directory.getReferences().size(), "current addresses survive a failed refresh");
    }

    @Test
    void foreignInterfaceIsFilteredOut() {
        TestableDirectory directory = newDirectory();
        MetadataInfo info = metadata(8080, "com.example.OrderService");
        directory.store.put(info.getRevision(), info);

        directory.notify(REGISTRY_URL, List.of(instance(8080, info.getRevision())));

        assertTrue(directory.getReferences().isEmpty(), "metadata of other interfaces must not become references");
    }

    @Test
    void instanceWithoutRevisionIsSkipped() {
        TestableDirectory directory = newDirectory();
        MetadataInfo info = metadata(8080, DemoService.class.getName());
        directory.store.put(info.getRevision(), info);

        directory.notify(REGISTRY_URL, List.of(instance(8080, null), instance(8081, info.getRevision())));

        assertEquals(1, directory.fetchCount.get());
        assertEquals(1, directory.getReferences().size());
    }

    @Test
    void emptyNotificationKeepsStaleReferences() {
        TestableDirectory directory = newDirectory();
        MetadataInfo info = metadata(8080, DemoService.class.getName());
        directory.store.put(info.getRevision(), info);
        directory.notify(REGISTRY_URL, List.of(instance(8080, info.getRevision())));

        directory.notify(REGISTRY_URL, List.of());

        assertEquals(1, directory.getReferences().size(), "empty protection inherited from RegistryDirectory");
    }

    // ---- stubs ----

    /** Directory with injected metadata fetching; counts real fetch attempts. */
    private static class TestableDirectory extends AppLevelRegistryDirectory<DemoService> {
        final Map<String, MetadataInfo> store = new HashMap<>();
        final AtomicInteger fetchCount = new AtomicInteger();
        boolean failFetch;

        TestableDirectory(URL refUrl, URL consumerUrl) {
            super(DemoService.class, refUrl, consumerUrl, List.of(REGISTRY_URL), new StubProtocol(), APP);
        }

        @Override
        protected MetadataInfo fetchMetadata(String revision, List<URL> instances) {
            fetchCount.incrementAndGet();
            return failFetch ? null : store.get(revision);
        }
    }

    /** Protocol stub: refer returns an offline reference, no transport involved. */
    private static class StubProtocol implements Protocol {
        @Override
        public <T> Exporter<T> export(Provider<T> provider) {
            throw new UnsupportedOperationException("not used in this test");
        }

        @Override
        public <T> Reference<T> refer(Class<T> interfaceClass, URL url) {
            return new StubReference<>(interfaceClass, url);
        }

        @Override
        public void unexport(URL url) {
        }

        @Override
        public void destroy() {
        }
    }

    private static class StubReference<T> implements Reference<T> {
        private final Class<T> interfaceClass;
        private final URL url;

        StubReference(Class<T> interfaceClass, URL url) {
            this.interfaceClass = interfaceClass;
            this.url = url;
        }

        @Override
        public int activeCallCount() {
            return 0;
        }

        @Override
        public URL getServiceUrl() {
            return url;
        }

        @Override
        public Class<T> getInterface() {
            return interfaceClass;
        }

        @Override
        public Response call(Request request) {
            throw new UnsupportedOperationException("not used in this test");
        }

        @Override
        public void init() {
        }

        @Override
        public void destroy() {
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String desc() {
            return url.toSimpleString();
        }

        @Override
        public URL getUrl() {
            return url;
        }
    }
}