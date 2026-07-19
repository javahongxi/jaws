package org.hongxi.jaws.cluster.ha;

import org.hongxi.jaws.cluster.LoadBalance;
import org.hongxi.jaws.exception.JawsBizException;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.URL;
import org.junit.jupiter.api.BeforeEach;
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
 * FailoverHaStrategy 单元测试
 */
class FailoverHaStrategyTest {

    private FailoverHaStrategy<String> strategy;

    @BeforeEach
    void setUp() {
        strategy = new FailoverHaStrategy<>();
    }

    /* ==================== 辅助 stub ==================== */

    private static class StubResponse implements Response {
        private final Object value;

        StubResponse() { this("ok"); }
        StubResponse(Object value) { this.value = value; }

        @Override public Object getValue() { return value; }
        @Override public Exception getException() { return null; }
        @Override public long getRequestId() { return 1L; }
        @Override public long getProcessTime() { return 0; }
        @Override public void setProcessTime(long time) {}
        @Override public int getTimeout() { return 0; }
        @Override public Map<String, String> getAttachments() { return new HashMap<>(); }
        @Override public void setAttachment(String key, String value) {}
        @Override public int getSerializationNumber() { return 0; }
        @Override public void setSerializationNumber(int number) {}
    }

    private static class StubRequest implements Request {
        private int retries;

        @Override public String getInterfaceName() { return "testService"; }
        @Override public String getMethodName() { return "testMethod"; }
        @Override public String getParametersDesc() { return ""; }
        @Override public Object[] getArguments() { return new Object[0]; }
        @Override public Map<String, String> getAttachments() { return new HashMap<>(); }
        @Override public void setAttachment(String name, String value) {}
        @Override public long getRequestId() { return 1L; }
        @Override public int getRetries() { return retries; }
        @Override public void setRetries(int retries) { this.retries = retries; }
        @Override public int getSerializationNumber() { return 0; }
        @Override public void setSerializationNumber(int number) {}
    }

    /**
     * 可配置行为的 Reference：支持设置成功/失败响应、调用计数
     */
    private static class StubReference implements Reference<String> {
        private final URL url;
        private final AtomicInteger callCount = new AtomicInteger(0);
        private final List<Response> responses = new ArrayList<>();
        private final List<RuntimeException> exceptions = new ArrayList<>();

        StubReference(URL url) {
            this.url = url;
        }

        /* 设置第 N 次调用的返回结果 */
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

    /**
     * 可配置 selectToHolder 返回顺序的 LoadBalance
     */
    private static class StubLoadBalance implements LoadBalance<String> {
        private final List<Reference<String>> refs = new ArrayList<>();

        StubLoadBalance(Reference<String>... references) {
            for (Reference<String> ref : references) {
                refs.add(ref);
            }
        }

        @Override public void onRefresh(List<Reference<String>> references) {}
        @Override public Reference<String> select(Request request) { return refs.get(0); }
        @Override public void selectToHolder(Request request, List<Reference<String>> refersHolder) {
            refersHolder.addAll(refs);
        }
        @Override public void setWeightString(String weightString) {}
        @Override public String getWeightString() { return null; }
    }

    private static URL urlWithRetries(int retries) {
        Map<String, String> params = new HashMap<>();
        params.put("retries", String.valueOf(retries));
        return new URL("jaws", "127.0.0.1", 8080, "testService", params);
    }

    private static URL defaultUrl() {
        return new URL("jaws", "127.0.0.1", 8080, "testService");
    }

    /* ==================== 测试用例 ==================== */

    @Test
    void firstCallSucceedsShouldReturnImmediately() {
        URL url = urlWithRetries(2);
        StubReference ref = new StubReference(url).thenReturn(new StubResponse("result-1"));
        StubLoadBalance lb = new StubLoadBalance(ref);
        StubRequest request = new StubRequest();

        Response result = strategy.call(request, lb);

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

        Response result = strategy.call(request, lb);

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

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> strategy.call(request, lb));

        assertEquals("err-2", thrown.getMessage());
        assertEquals(2, ref.getCallCount());
    }

    @Test
    void bizExceptionShouldNotRetry() {
        URL url = urlWithRetries(3);
        JawsBizException bizEx = new JawsBizException("biz error");
        StubReference ref = new StubReference(url).thenThrow(bizEx);
        StubLoadBalance lb = new StubLoadBalance(ref);
        StubRequest request = new StubRequest();

        RuntimeException thrown = assertThrows(JawsBizException.class, () -> strategy.call(request, lb));

        assertTrue(thrown.getMessage().contains("biz error"));
        assertEquals(1, ref.getCallCount());
    }

    @Test
    void emptyReferencesShouldThrowServiceException() {
        StubLoadBalance lb = new StubLoadBalance();
        StubRequest request = new StubRequest();

        assertThrows(JawsServiceException.class, () -> strategy.call(request, lb));
    }

    @Test
    void defaultRetriesIsZeroShouldNotRetry() {
        URL url = defaultUrl();
        StubReference ref = new StubReference(url).thenThrow(new RuntimeException("fail"));
        StubLoadBalance lb = new StubLoadBalance(ref);
        StubRequest request = new StubRequest();

        assertThrows(RuntimeException.class, () -> strategy.call(request, lb));

        assertEquals(1, ref.getCallCount());
    }

    @Test
    void negativeRetriesShouldBeTreatedAsZero() {
        URL url = urlWithRetries(-1);
        StubReference ref = new StubReference(url).thenThrow(new RuntimeException("fail"));
        StubLoadBalance lb = new StubLoadBalance(ref);
        StubRequest request = new StubRequest();

        assertThrows(RuntimeException.class, () -> strategy.call(request, lb));

        assertEquals(1, ref.getCallCount());
    }

    @Test
    void multipleReferencesShouldRoundRobin() {
        URL url1 = urlWithRetries(2);
        URL url2 = new URL("jaws", "127.0.0.2", 8080, "testService",
                url1.getParameters());
        StubReference ref1 = new StubReference(url1).thenThrow(new RuntimeException("fail-1"));
        StubReference ref2 = new StubReference(url2).thenReturn(new StubResponse("from-ref2"));
        StubLoadBalance lb = new StubLoadBalance(ref1, ref2);
        StubRequest request = new StubRequest();

        Response result = strategy.call(request, lb);

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

        strategy.call(request, lb);

        assertEquals(url, org.hongxi.jaws.rpc.RpcContext.getContext().getServerUrl());
    }

    @Test
    void requestRetriesShouldBeUpdatedOnEachAttempt() {
        URL url = urlWithRetries(2);
        StubReference ref = new StubReference(url)
                .thenThrow(new RuntimeException("fail-1"))
                .thenThrow(new RuntimeException("fail-2"))
                .thenReturn(new StubResponse("ok"));
        StubLoadBalance lb = new StubLoadBalance(ref);

        List<Integer> retriesSeen = new ArrayList<>();
        StubRequest request = new StubRequest() {
            @Override
            public void setRetries(int retries) {
                super.setRetries(retries);
                retriesSeen.add(retries);
            }
        };

        strategy.call(request, lb);

        assertEquals(List.of(0, 1, 2), retriesSeen);
    }
}
