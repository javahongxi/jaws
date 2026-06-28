package org.hongxi.jaws.cluster.loadbalance;

import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.Request;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RoundRobinLoadBalance 单元测试
 */
class RoundRobinLoadBalanceTest {

    private RoundRobinLoadBalance<String> lb;
    private Request request;

    @BeforeEach
    void setUp() {
        lb = new RoundRobinLoadBalance<>();
        request = mockRequest();
    }

    @Test
    void selectShouldReturnAvailableReference() {
        List<Reference<String>> refs = createRefs("A", "B", "C");
        lb.onRefresh(refs);

        for (int i = 0; i < 100; i++) {
            Reference<String> selected = lb.select(request);
            assertTrue(selected.isAvailable());
        }
    }

    @Test
    void selectShouldCycleThroughAllReferences() {
        List<Reference<String>> refs = createRefs("A", "B", "C");
        lb.onRefresh(refs);

        /* 足够多的调用，确保所有 reference 都被选中 */
        Set<String> hit = new HashSet<>();
        for (int i = 0; i < 300; i++) {
            hit.add(((TestReference) lb.select(request)).getName());
        }
        assertEquals(3, hit.size(), "轮询策略应命中所有 reference");
    }

    @Test
    void selectShouldDistributeEvenly() {
        List<Reference<String>> refs = createRefs("A", "B", "C");
        lb.onRefresh(refs);

        int total = 300;
        int[] counts = new int[3];
        for (int i = 0; i < total; i++) {
            String name = ((TestReference) lb.select(request)).getName();
            counts[name.charAt(0) - 'A']++;
        }

        /* 轮询应均匀分配，每个约 100 次 */
        for (int count : counts) {
            assertEquals(100, count, "轮询策略应均匀分配调用次数");
        }
    }

    @Test
    void selectShouldSkipUnavailableReference() {
        List<Reference<String>> refs = createRefs("A", "B", "C");
        /* 将第一个创建的 reference 标记为不可用 */
        ((TestReference) refs.get(0)).setAvailable(false);
        lb.onRefresh(refs);

        for (int i = 0; i < 100; i++) {
            Reference<String> selected = lb.select(request);
            assertNotEquals("A", ((TestReference) selected).getName());
        }
    }

    @Test
    void selectSingleReference() {
        List<Reference<String>> refs = createRefs("only");
        lb.onRefresh(refs);

        for (int i = 0; i < 10; i++) {
            assertEquals("only", ((TestReference) lb.select(request)).getName());
        }
    }

    private List<Reference<String>> createRefs(String... names) {
        List<Reference<String>> list = new ArrayList<>();
        for (String name : names) {
            list.add(new TestReference(name));
        }
        return list;
    }

    private Request mockRequest() {
        return new Request() {
            @Override public String getInterfaceName() { return "TestService"; }
            @Override public String getMethodName() { return "test"; }
            @Override public String getParametersDesc() { return ""; }
            @Override public Object[] getArguments() { return null; }
            @Override public java.util.Map<String, String> getAttachments() { return null; }
            @Override public void setAttachment(String name, String value) {}
            @Override public long getRequestId() { return 0; }
            @Override public int getRetries() { return 0; }
            @Override public void setRetries(int retries) {}
            @Override public int getSerializationNumber() { return 0; }
            @Override public void setSerializationNumber(int number) {}
        };
    }
}
