package org.hongxi.jaws.registry;

import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.rpc.URL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the failback retry mechanism: failures are queued and
 * retried per URL (one failing URL must not block the others), successful
 * retries remove the entry from the queue, and check mode fails fast.
 */
class FailbackRegistryTest {

    private TestRegistry registry;

    /**
     * Stub registry whose do* operations fail on demand (globally or per URL).
     */
    static class TestRegistry extends FailbackRegistry {

        final Map<String, AtomicInteger> registerAttempts = new ConcurrentHashMap<>();
        final Map<String, AtomicInteger> unregisterAttempts = new ConcurrentHashMap<>();
        final AtomicInteger subscribeAttempts = new AtomicInteger();
        final AtomicInteger unsubscribeAttempts = new AtomicInteger();

        volatile boolean failAll;
        final Set<String> failingPaths = ConcurrentHashMap.newKeySet();

        TestRegistry(URL url) {
            super(url);
        }

        private boolean shouldFail(URL url) {
            return failAll || failingPaths.contains(url.getPath());
        }

        @Override
        protected void doRegister(URL url) {
            registerAttempts.computeIfAbsent(url.getPath(), k -> new AtomicInteger()).incrementAndGet();
            if (shouldFail(url)) {
                throw new RuntimeException("registry down");
            }
        }

        @Override
        protected void doUnregister(URL url) {
            unregisterAttempts.computeIfAbsent(url.getPath(), k -> new AtomicInteger()).incrementAndGet();
            if (shouldFail(url)) {
                throw new RuntimeException("registry down");
            }
        }

        @Override
        protected void doSubscribe(URL url, NotifyListener listener) {
            subscribeAttempts.incrementAndGet();
            if (shouldFail(url)) {
                throw new RuntimeException("registry down");
            }
        }

        @Override
        protected void doUnsubscribe(URL url, NotifyListener listener) {
            unsubscribeAttempts.incrementAndGet();
            if (shouldFail(url)) {
                throw new RuntimeException("registry down");
            }
        }

        @Override
        protected List<URL> doDiscover(URL url) {
            return List.of();
        }
    }

    @BeforeEach
    void setUp() {
        registry = new TestRegistry(registryUrl());
    }

    private static URL registryUrl() {
        Map<String, String> params = new HashMap<>();
        // Long period: tests drive retry() directly instead of the scheduler
        params.put(UrlParam.Registry.RETRY_PERIOD.getName(), "60000");
        return new URL("jaws", "127.0.0.1", 2181, "registry", params);
    }

    private static URL serviceUrl(String path) {
        Map<String, String> params = new HashMap<>();
        params.put(UrlParam.Client.CHECK.getName(), "false");
        return new URL("jaws", "127.0.0.1", 20880, path, params);
    }

    @Test
    void registerFailureIsQueuedAndRetried() {
        registry.failAll = true;
        URL url = serviceUrl("serviceA");

        registry.register(url);
        assertEquals(1, registry.registerAttempts.get("serviceA").get());

        registry.failAll = false;
        registry.retry();
        assertEquals(2, registry.registerAttempts.get("serviceA").get());

        // Successful retry removes the entry: further retries do nothing
        registry.retry();
        assertEquals(2, registry.registerAttempts.get("serviceA").get());
    }

    @Test
    void retryIsPerUrlSoOneFailureDoesNotBlockOthers() {
        registry.failAll = true;
        URL url1 = serviceUrl("serviceA");
        URL url2 = serviceUrl("serviceB");
        registry.register(url1);
        registry.register(url2);

        // serviceA keeps failing while serviceB recovers
        registry.failAll = false;
        registry.failingPaths.add("serviceA");
        registry.retry();

        assertEquals(2, registry.registerAttempts.get("serviceA").get());
        assertEquals(2, registry.registerAttempts.get("serviceB").get());

        // serviceB is out of the queue; serviceA is still retried
        registry.retry();
        assertEquals(3, registry.registerAttempts.get("serviceA").get());
        assertEquals(2, registry.registerAttempts.get("serviceB").get());
    }

    @Test
    void unregisterFailureIsQueuedAndRetried() {
        URL url = serviceUrl("serviceA");
        registry.register(url);

        registry.failAll = true;
        registry.unregister(url);
        assertEquals(1, registry.unregisterAttempts.get("serviceA").get());

        registry.failAll = false;
        registry.retry();
        assertEquals(2, registry.unregisterAttempts.get("serviceA").get());

        registry.retry();
        assertEquals(2, registry.unregisterAttempts.get("serviceA").get());
    }

    @Test
    void subscribeFailureIsRetriedAndEntryCleaned() {
        URL url = serviceUrl("serviceA");
        NotifyListener listener = (registryUrl, urls) -> { };

        registry.failAll = true;
        registry.subscribe(url, listener);
        assertEquals(1, registry.subscribeAttempts.get());

        registry.failAll = false;
        registry.retry();
        assertEquals(2, registry.subscribeAttempts.get());

        // Empty listener set must be cleaned up: no further attempts
        registry.retry();
        assertEquals(2, registry.subscribeAttempts.get());
    }

    @Test
    void unsubscribeFailureIsRetriedAndEntryCleaned() {
        URL url = serviceUrl("serviceA");
        NotifyListener listener = (registryUrl, urls) -> { };
        registry.subscribe(url, listener);

        registry.failAll = true;
        registry.unsubscribe(url, listener);
        assertEquals(1, registry.unsubscribeAttempts.get());

        registry.failAll = false;
        registry.retry();
        assertEquals(2, registry.unsubscribeAttempts.get());

        registry.retry();
        assertEquals(2, registry.unsubscribeAttempts.get());
    }

    @Test
    void checkModeFailsFastInsteadOfQueuing() {
        Map<String, String> params = new HashMap<>();
        params.put(UrlParam.Registry.RETRY_PERIOD.getName(), "60000");
        params.put(UrlParam.Client.CHECK.getName(), "true");
        TestRegistry checked = new TestRegistry(new URL("jaws", "127.0.0.1", 2181, "registry", params));
        checked.failAll = true;

        URL url = serviceUrl("serviceA");
        url.addParameter(UrlParam.Client.CHECK.getName(), "true");
        assertThrows(JawsFrameworkException.class, () -> checked.register(url));
    }

    @Test
    void recoverRequeuesRegisteredAndSubscribed() {
        URL url = serviceUrl("serviceA");
        NotifyListener listener = (registryUrl, urls) -> { };
        registry.register(url);
        registry.subscribe(url, listener);
        assertTrue(registry.getRegistered().contains(url));
        assertTrue(registry.getSubscribed().containsKey(url));

        registry.failAll = true;
        registry.recover();
        registry.failAll = false;
        registry.retry();

        // register: initial + retry; subscribe: initial + retry
        assertEquals(2, registry.registerAttempts.get("serviceA").get());
        assertEquals(2, registry.subscribeAttempts.get());
    }
}
