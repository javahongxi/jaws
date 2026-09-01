package org.hongxi.jaws.cluster.support;

import org.hongxi.jaws.cluster.LoadBalance;
import org.hongxi.jaws.exception.JawsBizException;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.URL;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FailoverCluster unit tests
 */
class FailoverClusterTest {

    /* ==================== stubs ==================== */

    private static class StubResponse implements Response {
        private final Object value;

        StubResponse() { this("ok"); }
        StubResponse(Object value) { this.value = value; }

        @Override public Object getValue() { return value; }
        @Override public Object getRawValue() { return value; }
        @Override public Throwable getThrowable() { return null; }
        @Override public long getRequestId() { return 1L; }
        @Override public long getProcessTime() { return 0; }
        public void setProcessTime(long time) {}
        @Override public int getTimeout() { return 0; }
        public void setTimeout(int timeout) {}
        @Override public Map<String, String> getAttachments() { return new HashMap<>(); }
        @Override public void setAttachment(String key, String value) {}
        @Override public byte getSerializationNumber() { return 0; }
        public void setSerializationNumber(byte number) {}
    }

    private static class StubRequest extends DefaultRequest {
        StubRequest() {
            setInterfaceName("testService");
            setMethodName("testMethod");
        }
    }

    private static class StubReference implements Reference<String> {
        private final URL url;
        private final AtomicInteger callCount = new AtomicInteger(0);
        private final List<Response> responses = new ArrayList<>();
        private final List<RuntimeException> exceptions = new ArrayList<>();

        StubReference(URL url) { this.url = url; }

        StubReference thenReturn(Response response) {
            responses.add(response);
            exceptions.add(null);
            return this;
        }

        StubReference thenThrow(RuntimeException ex) {
            responses.add(null);
            exceptions.add(ex);
            return this;
        }

        int getCallCount() { return callCount.get(); }

        @Override public URL getUrl() { return url; }
        @Override public URL getServiceUrl() { return url; }
        @Override public Class<String> getInterface() { return String.class; }
        @Override public int activeReferenceCount() { return 0; }
        @Override public void init() {}
        @Override public void destroy() {}
        @Override public boolean isAvailable() { return true; }
        @Override public String desc() { return "stub-ref"; }

        @Override
        public Response call(Request request) {
            int idx = callCount.getAndIncrement();
            if (idx < exceptions.size() && exceptions.get(idx) != null) {
                throw exceptions.get(idx);
            }
            if (idx < responses.size()) {
                return responses.get(idx);
            }
            return new StubResponse();
        }
    }

    private static class StubLoadBalance implements LoadBalance<String> {
        private final List<Reference<String>> refs = new ArrayList<>();

        StubLoadBalance(Reference<String>... references) {
            for (Reference<String> ref : references) {
                refs.add(ref);
            }
        }

        @Override public void onRefresh(List<Reference<String>> references) {}
        @Override public Reference<String> select(Request request) { return refs.get(0); }
        @Override public List<Reference<String>> selectCandidates(Request request) {
            return new ArrayList<>(refs);
        }
    }

    private static URL urlWithRetries(int retries) {
        Map<String, String> params = new HashMap<>();
        params.put("retries", String.valueOf(retries));
        return new URL("jaws", "127.0.0.1", 8080, "testService", params);
    }

    private static URL defaultUrl() {
        return new URL("jaws", "127.0.0.1", 8080, "testService");
    }

    private FailoverCluster<String> newCluster(URL url, LoadBalance<String> lb) {
        FailoverCluster<String> cluster = new FailoverCluster<>(url, lb);
        cluster.init();
        return cluster;
    }

    /* ==================== test cases ==================== */

    @Test
    void firstCallSucceedsShouldReturnImmediately() {
        URL url = urlWithRetries(2);
        StubReference ref = new StubReference(url).thenReturn(new StubResponse("result-1"));
        StubLoadBalance lb = new StubLoadBalance(ref);
        StubRequest request = new StubRequest();
        FailoverCluster<String> cluster = newCluster(url, lb);

        Response result = cluster.call(request);

        assertEquals("result-1", result.getValue());
        assertEquals(1, ref.getCallCount());
    }

    @Test
    void frameworkExceptionThenRetryShouldSucceed() {
        URL url = urlWithRetries(2);
        StubReference ref = new StubReference(url)
                .thenThrow(new RuntimeException("timeout"))
                .thenReturn(new StubResponse("retry-ok"));
        StubLoadBalance lb = new StubLoadBalance(ref);
        StubRequest request = new StubRequest();
        FailoverCluster<String> cluster = newCluster(url, lb);

        Response result = cluster.call(request);

        assertEquals("retry-ok", result.getValue());
        assertEquals(2, ref.getCallCount());
    }

    @Test
    void allRetriesFailShouldThrowLastException() {
        URL url = urlWithRetries(1);
        RuntimeException firstEx = new RuntimeException("err-1");
        RuntimeException secondEx = new RuntimeException("err-2");
        StubReference ref = new StubReference(url)
                .thenThrow(firstEx)
                .thenThrow(secondEx);
        StubLoadBalance lb = new StubLoadBalance(ref);
        StubRequest request = new StubRequest();
        FailoverCluster<String> cluster = newCluster(url, lb);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> cluster.call(request));

        assertTrue(thrown.getMessage().contains("FailoverCluster call failed"));
        assertEquals(secondEx, thrown.getCause());
        assertEquals(2, ref.getCallCount());
    }

    @Test
    void bizExceptionShouldNotRetry() {
        URL url = urlWithRetries(3);
        JawsBizException bizEx = new JawsBizException("biz error");
        StubReference ref = new StubReference(url).thenThrow(bizEx);
        StubLoadBalance lb = new StubLoadBalance(ref);
        StubRequest request = new StubRequest();
        FailoverCluster<String> cluster = newCluster(url, lb);

        RuntimeException thrown = assertThrows(JawsBizException.class, () -> cluster.call(request));

        assertTrue(thrown.getMessage().contains("biz error"));
        assertEquals(1, ref.getCallCount());
    }

    @Test
    void emptyReferencesShouldThrowServiceException() {
        URL url = defaultUrl();
        StubLoadBalance lb = new StubLoadBalance();
        StubRequest request = new StubRequest();
        FailoverCluster<String> cluster = newCluster(url, lb);

        assertThrows(JawsServiceException.class, () -> cluster.call(request));
    }

    @Test
    void defaultRetriesIsZeroShouldNotRetry() {
        URL url = defaultUrl();
        StubReference ref = new StubReference(url).thenThrow(new RuntimeException("fail"));
        StubLoadBalance lb = new StubLoadBalance(ref);
        StubRequest request = new StubRequest();
        FailoverCluster<String> cluster = newCluster(url, lb);

        assertThrows(RuntimeException.class, () -> cluster.call(request));

        assertEquals(1, ref.getCallCount());
    }

    @Test
    void negativeRetriesShouldBeTreatedAsZero() {
        URL url = urlWithRetries(-1);
        StubReference ref = new StubReference(url).thenThrow(new RuntimeException("fail"));
        StubLoadBalance lb = new StubLoadBalance(ref);
        StubRequest request = new StubRequest();
        FailoverCluster<String> cluster = newCluster(url, lb);

        assertThrows(RuntimeException.class, () -> cluster.call(request));

        assertEquals(1, ref.getCallCount());
    }

    @Test
    void multipleReferencesShouldRoundRobin() {
        URL url1 = urlWithRetries(2);
        URL url2 = new URL("jaws", "127.0.0.2", 8080, "testService", url1.getParameters());
        StubReference ref1 = new StubReference(url1).thenThrow(new RuntimeException("fail-1"));
        StubReference ref2 = new StubReference(url2).thenReturn(new StubResponse("from-ref2"));
        StubLoadBalance lb = new StubLoadBalance(ref1, ref2);
        StubRequest request = new StubRequest();
        FailoverCluster<String> cluster = newCluster(url1, lb);

        Response result = cluster.call(request);

        assertEquals("from-ref2", result.getValue());
        assertEquals(1, ref1.getCallCount());
        assertEquals(1, ref2.getCallCount());
    }

    @Test
    void rpcContextServerUrlShouldBeSet() {
        URL url = urlWithRetries(0);
        StubReference ref = new StubReference(url).thenReturn(new StubResponse());
        StubLoadBalance lb = new StubLoadBalance(ref);
        StubRequest request = new StubRequest();
        FailoverCluster<String> cluster = newCluster(url, lb);

        cluster.call(request);

        assertEquals(url, org.hongxi.jaws.rpc.RpcContext.getContext().getServerUrl());
    }


}
