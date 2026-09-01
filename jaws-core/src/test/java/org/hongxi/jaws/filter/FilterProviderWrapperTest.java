package org.hongxi.jaws.filter;

import org.hongxi.jaws.rpc.Caller;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.DefaultResponse;
import org.hongxi.jaws.rpc.Provider;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.URL;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that verify provider-side filters are applied on both sync and async paths.
 */
class FilterProviderWrapperTest {

    private static final URL TEST_URL = new URL("jaws", "127.0.0.1", 20880, "test.Service");

    /* ==================== stubs ==================== */

    /**
     * A provider that returns a completed future with a fixed response.
     */
    private static class StubProvider implements Provider<String> {
        private final Response response;
        private final AtomicInteger callCount = new AtomicInteger(0);
        private final AtomicInteger callAsyncCount = new AtomicInteger(0);

        StubProvider(Response response) {
            this.response = response;
        }

        @Override
        public Response call(Request request) {
            callCount.incrementAndGet();
            return response;
        }

        @Override
        public CompletableFuture<Response> callAsync(Request request) {
            callAsyncCount.incrementAndGet();
            return CompletableFuture.completedFuture(response);
        }

        @Override public Method lookupMethod(String methodName, String paramDesc) { return null; }
        @Override public String getImpl() { return "stub"; }
        @Override public Class<String> getInterface() { return String.class; }
        @Override public URL getUrl() { return TEST_URL; }
        @Override public void init() {}
        @Override public void destroy() {}
        @Override public boolean isAvailable() { return true; }
        @Override public String desc() { return "stub-provider"; }

        int getCallCount() { return callCount.get(); }
        int getCallAsyncCount() { return callAsyncCount.get(); }
    }

    /**
     * A filter that tracks invocations and delegates to the next caller.
     */
    private static class TrackingFilter implements Filter {
        private final AtomicInteger filterCount = new AtomicInteger(0);
        private final AtomicBoolean usedCallAsync = new AtomicBoolean(false);

        @Override
        public CompletableFuture<Response> filter(Caller<?> caller, Request request) {
            filterCount.incrementAndGet();
            return caller.callAsync(request);
        }

        int getFilterCount() { return filterCount.get(); }
        boolean usedCallAsync() { return usedCallAsync.get(); }
    }

    /* ==================== test cases ==================== */

    @Test
    void callAsyncShouldInvokeFilter() {
        DefaultResponse expected = new DefaultResponse();
        expected.setValue("hello");
        StubProvider provider = new StubProvider(expected);
        TrackingFilter filter = new TrackingFilter();
        FilterProviderWrapper<String> wrapper = new FilterProviderWrapper<>(provider, filter);

        DefaultRequest request = new DefaultRequest();
        request.setInterfaceName("test.Service");
        request.setMethodName("greet");

        CompletableFuture<Response> future = wrapper.callAsync(request);

        assertNotNull(future);
        Response response = future.join();
        assertSame(expected, response);
        assertEquals(1, filter.getFilterCount());
        assertEquals(0, provider.getCallCount());       // sync call should NOT be used
        assertEquals(1, provider.getCallAsyncCount());   // async call should be used
    }

    @Test
    void callShouldInvokeFilterAndBlock() {
        DefaultResponse expected = new DefaultResponse();
        expected.setValue("hello");
        StubProvider provider = new StubProvider(expected);
        TrackingFilter filter = new TrackingFilter();
        FilterProviderWrapper<String> wrapper = new FilterProviderWrapper<>(provider, filter);

        DefaultRequest request = new DefaultRequest();
        request.setInterfaceName("test.Service");
        request.setMethodName("greet");

        Response response = wrapper.call(request);

        assertSame(expected, response);
        assertEquals(1, filter.getFilterCount());
        // call() uses .join() which internally calls filter.filter() → caller.callAsync()
        assertEquals(1, provider.getCallAsyncCount());
    }

    @Test
    void filterShouldBeAbleToRejectBeforeDelegating() {
        StubProvider provider = new StubProvider(new DefaultResponse());
        Filter rejectingFilter = (caller, request) ->
                CompletableFuture.failedFuture(
                        new RuntimeException("rejected by filter"));
        FilterProviderWrapper<String> wrapper = new FilterProviderWrapper<>(provider, rejectingFilter);

        DefaultRequest request = new DefaultRequest();
        request.setInterfaceName("test.Service");

        CompletableFuture<Response> future = wrapper.callAsync(request);

        assertNotNull(future);
        assertTrue(future.isCompletedExceptionally());
        assertEquals(0, provider.getCallAsyncCount()); // provider should NOT be called
    }

    @Test
    void filterShouldBeAbleToModifyResponse() {
        DefaultResponse original = new DefaultResponse();
        original.setValue("original");
        StubProvider provider = new StubProvider(original);

        Filter modifyingFilter = (caller, request) ->
                caller.callAsync(request).thenApply(resp -> {
                    DefaultResponse modified = new DefaultResponse();
                    modified.setValue("modified");
                    return modified;
                });

        FilterProviderWrapper<String> wrapper = new FilterProviderWrapper<>(provider, modifyingFilter);

        DefaultRequest request = new DefaultRequest();
        request.setInterfaceName("test.Service");

        Response response = wrapper.callAsync(request).join();

        assertEquals("modified", response.getValue());
    }
}
