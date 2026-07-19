package org.hongxi.jaws.cluster.ha;

import org.hongxi.jaws.cluster.LoadBalance;
import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.URL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * FailfastHaStrategy 单元测试
 */
class FailfastHaStrategyTest {

    private FailfastHaStrategy<String> strategy;
    private URL testUrl;

    @BeforeEach
    void setUp() {
        strategy = new FailfastHaStrategy<>();
        testUrl = new URL("jaws", "127.0.0.1", 8080, "testService");
    }

    /* ==================== 辅助 stub ==================== */

    private static class StubResponse implements Response {
        @Override public Object getValue() { return "ok"; }
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
        @Override public String getInterfaceName() { return "testService"; }
        @Override public String getMethodName() { return "testMethod"; }
        @Override public String getParametersDesc() { return ""; }
        @Override public Object[] getArguments() { return new Object[0]; }
        @Override public Map<String, String> getAttachments() { return new HashMap<>(); }
        @Override public void setAttachment(String name, String value) {}
        @Override public long getRequestId() { return 1L; }
        @Override public int getRetries() { return 0; }
        @Override public void setRetries(int retries) {}
        @Override public int getSerializationNumber() { return 0; }
        @Override public void setSerializationNumber(int number) {}
    }

    private static class StubReference implements Reference<String> {
        private final URL url;
        private final Response response;
        private final RuntimeException exception;
        private int callCount = 0;

        StubReference(URL url, Response response) {
            this.url = url;
            this.response = response;
            this.exception = null;
        }

        StubReference(URL url, RuntimeException exception) {
            this.url = url;
            this.response = null;
            this.exception = exception;
        }

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
            callCount++;
            if (exception != null) {
                throw exception;
            }
            return response;
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
        @Override public void selectToHolder(Request request, List<Reference<String>> refersHolder) {
            refersHolder.add(selectedRef);
        }
        @Override public void setWeightString(String weightString) {}
        @Override public String getWeightString() { return null; }
    }

    /* ==================== 测试用例 ==================== */

    @Test
    void callShouldReturnResponseFromSelectedReference() {
        StubResponse expected = new StubResponse();
        StubReference ref = new StubReference(testUrl, expected);
        StubLoadBalance lb = new StubLoadBalance(ref);
        StubRequest request = new StubRequest();

        Response result = strategy.call(request, lb);

        assertEquals(expected, result);
        assertEquals(1, ref.getCallCount());
    }

    @Test
    void callShouldSetRpcContextServerUrl() {
        StubReference ref = new StubReference(testUrl, new StubResponse());
        StubLoadBalance lb = new StubLoadBalance(ref);
        StubRequest request = new StubRequest();

        strategy.call(request, lb);

        assertEquals(testUrl, org.hongxi.jaws.rpc.RpcContext.getContext().getServerUrl());
    }

    @Test
    void callShouldPropagateExceptionWithoutRetry() {
        RuntimeException ex = new RuntimeException("network error");
        StubReference ref = new StubReference(testUrl, ex);
        StubLoadBalance lb = new StubLoadBalance(ref);
        StubRequest request = new StubRequest();

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> strategy.call(request, lb));

        assertEquals("network error", thrown.getMessage());
        assertEquals(1, ref.getCallCount());
    }
}
