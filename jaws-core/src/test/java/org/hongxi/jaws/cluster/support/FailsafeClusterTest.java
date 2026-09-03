package org.hongxi.jaws.cluster.support;

import org.hongxi.jaws.cluster.LoadBalance;
import org.hongxi.jaws.exception.JawsBizException;
import org.hongxi.jaws.rpc.DefaultResponse;
import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.URL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * FailsafeCluster unit tests
 */
class FailsafeClusterTest {

    private URL testUrl;

    @BeforeEach
    void setUp() {
        testUrl = new URL("jaws", "127.0.0.1", 8080, "testService");
    }

    /* ==================== stubs ==================== */

    private static class StubResponse implements Response {
        @Override public Object getValue() { return "ok"; }
        @Override public Object getRawValue() { return "ok"; }
        @Override public Throwable getThrowable() { return null; }
        @Override public long getRequestId() { return 1L; }
        @Override public long getProcessTime() { return 0; }
        public void setProcessTime(long time) {}
        @Override public int getTimeout() { return 0; }
        @Override public Map<String, String> getAttachments() { return new HashMap<>(); }
        @Override public void setAttachment(String key, String value) {}
        @Override public byte getSerializationNumber() { return 0; }
        public void setSerializationNumber(byte number) {}
    }

    private static class StubRequest implements Request {
        @Override public String getInterfaceName() { return "testService"; }
        @Override public String getMethodName() { return "testMethod"; }
        @Override public String getParamDesc() { return ""; }
        @Override public Object[] getArguments() { return new Object[0]; }
        @Override public Map<String, String> getAttachments() { return new HashMap<>(); }
        @Override public void setAttachment(String name, String value) {}
        @Override public long getRequestId() { return 1L; }
        @Override public int getRetries() { return 0; }
        @Override public byte getSerializationNumber() { return 0; }
    }

    private static class StubReference implements Reference<String> {
        private final URL url;
        private final Response response;
        private final RuntimeException syncException;
        private final CompletableFuture<Response> asyncFuture;
        private int callCount = 0;

        StubReference(URL url, Response response) {
            this.url = url;
            this.response = response;
            this.syncException = null;
            this.asyncFuture = null;
        }

        StubReference(URL url, RuntimeException exception) {
            this.url = url;
            this.response = null;
            this.syncException = exception;
            this.asyncFuture = null;
        }

        StubReference(URL url, CompletableFuture<Response> asyncFuture) {
            this.url = url;
            this.response = null;
            this.syncException = null;
            this.asyncFuture = asyncFuture;
        }

        @Override public URL getUrl() { return url; }
        @Override public URL getServiceUrl() { return url; }
        @Override public Class<String> getInterface() { return String.class; }
        @Override public int activeCallCount() { return 0; }
        @Override public void init() {}
        @Override public void destroy() {}
        @Override public boolean isAvailable() { return true; }
        @Override public String desc() { return "stub-ref"; }

        @Override
        public Response call(Request request) {
            callCount++;
            if (syncException != null) {
                throw syncException;
            }
            return response;
        }

        @Override
        public CompletableFuture<Response> callAsync(Request request) {
            callCount++;
            if (asyncFuture != null) {
                return asyncFuture;
            }
            if (syncException != null) {
                throw syncException;
            }
            return CompletableFuture.completedFuture(response);
        }

        int getCallCount() { return callCount; }
    }

    private static class StubLoadBalance implements LoadBalance<String> {
        private final Reference<String> selectedRef;

        StubLoadBalance(Reference<String> ref) {
            this.selectedRef = ref;
        }

        @Override public void onRefresh(List<Reference<String>> references) {}
        @Override public Reference<String> select(Request request) { return selectedRef; }
        @Override public List<Reference<String>> selectCandidates(Request request) {
            return List.of(selectedRef);
        }
    }

    /* ==================== call() tests ==================== */

    @Test
    void callShouldReturnResponseFromSelectedReference() {
        StubResponse expected = new StubResponse();
        StubReference ref = new StubReference(testUrl, expected);
        StubLoadBalance lb = new StubLoadBalance(ref);
        StubRequest request = new StubRequest();
        FailsafeCluster<String> cluster = newCluster(lb);

        Response result = cluster.call(request);

        assertSame(expected, result);
        assertEquals(1, ref.getCallCount());
    }

    @Test
    void callShouldSetRpcContextServerUrl() {
        StubReference ref = new StubReference(testUrl, new StubResponse());
        StubLoadBalance lb = new StubLoadBalance(ref);
        StubRequest request = new StubRequest();
        FailsafeCluster<String> cluster = newCluster(lb);

        cluster.call(request);

        assertEquals(testUrl, org.hongxi.jaws.rpc.RpcContext.getContext().getServerUrl());
    }

    @Test
    void callShouldReturnEmptyResponseOnFrameworkException() {
        RuntimeException ex = new RuntimeException("network error");
        StubReference ref = new StubReference(testUrl, ex);
        StubLoadBalance lb = new StubLoadBalance(ref);
        StubRequest request = new StubRequest();
        FailsafeCluster<String> cluster = newCluster(lb);

        Response result = cluster.call(request);

        assertNotNull(result);
        assertNull(result.getThrowable());
        assertNull(result.getValue());
        assertEquals(1, ref.getCallCount());
    }

    @Test
    void callShouldPropagateBizException() {
        JawsBizException bizEx = new JawsBizException("biz error");
        StubReference ref = new StubReference(testUrl, bizEx);
        StubLoadBalance lb = new StubLoadBalance(ref);
        StubRequest request = new StubRequest();
        FailsafeCluster<String> cluster = newCluster(lb);

        assertThrows(JawsBizException.class, () -> cluster.call(request));
        assertEquals(1, ref.getCallCount());
    }

    @Test
    void callShouldReturnEmptyResponseWhenClusterNotAvailable() {
        StubReference ref = new StubReference(testUrl, new StubResponse());
        StubLoadBalance lb = new StubLoadBalance(ref);
        StubRequest request = new StubRequest();
        FailsafeCluster<String> cluster = new FailsafeCluster<>(testUrl, lb);
        // not calling cluster.init(), so available == false

        Response result = cluster.call(request);

        assertNotNull(result);
        assertNull(result.getThrowable());
        assertEquals(0, ref.getCallCount());
    }

    /* ==================== callAsync() tests ==================== */

    @Test
    void callAsyncShouldReturnResponseFromReference() {
        StubResponse expected = new StubResponse();
        StubReference ref = new StubReference(testUrl, expected);
        StubLoadBalance lb = new StubLoadBalance(ref);
        StubRequest request = new StubRequest();
        FailsafeCluster<String> cluster = newCluster(lb);

        CompletableFuture<Response> future = cluster.callAsync(request);

        assertNotNull(future);
        assertSame(expected, future.join());
        assertEquals(1, ref.getCallCount());
    }

    @Test
    void callAsyncShouldReturnEmptyResponseOnAsyncExceptionalCompletion() {
        RuntimeException ex = new RuntimeException("async network error");
        StubReference ref = new StubReference(testUrl, CompletableFuture.failedFuture(ex));
        StubLoadBalance lb = new StubLoadBalance(ref);
        StubRequest request = new StubRequest();
        FailsafeCluster<String> cluster = newCluster(lb);

        CompletableFuture<Response> future = cluster.callAsync(request);
        Response result = future.join();

        assertNotNull(result);
        assertNull(result.getThrowable());
        assertEquals(1, ref.getCallCount());
    }

    @Test
    void callAsyncShouldPropagateBizExceptionOnAsyncExceptionalCompletion() {
        JawsBizException bizEx = new JawsBizException("async biz error");
        StubReference ref = new StubReference(testUrl, CompletableFuture.failedFuture(bizEx));
        StubLoadBalance lb = new StubLoadBalance(ref);
        StubRequest request = new StubRequest();
        FailsafeCluster<String> cluster = newCluster(lb);

        CompletableFuture<Response> future = cluster.callAsync(request);

        assertThrows(Exception.class, () -> future.join());
        assertEquals(1, ref.getCallCount());
    }

    @Test
    void callAsyncShouldPropagateSyncExceptionFromReference() {
        RuntimeException ex = new RuntimeException("sync throw");
        StubReference ref = new StubReference(testUrl, ex);
        StubLoadBalance lb = new StubLoadBalance(ref);
        StubRequest request = new StubRequest();
        FailsafeCluster<String> cluster = newCluster(lb);

        assertThrows(RuntimeException.class, () -> cluster.callAsync(request));
        assertEquals(1, ref.getCallCount());
    }

    @Test
    void callAsyncShouldReturnEmptyResponseWhenClusterNotAvailable() {
        StubReference ref = new StubReference(testUrl, new StubResponse());
        StubLoadBalance lb = new StubLoadBalance(ref);
        StubRequest request = new StubRequest();
        FailsafeCluster<String> cluster = new FailsafeCluster<>(testUrl, lb);
        // not calling cluster.init(), so available == false

        CompletableFuture<Response> future = cluster.callAsync(request);
        Response result = future.join();

        assertNotNull(result);
        assertNull(result.getThrowable());
        assertEquals(0, ref.getCallCount());
    }

    private FailsafeCluster<String> newCluster(LoadBalance<String> lb) {
        FailsafeCluster<String> cluster = new FailsafeCluster<>(testUrl, lb);
        cluster.init();
        return cluster;
    }
}
