package org.hongxi.jaws.cluster.loadbalance;

import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.Request;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LeastActiveLoadBalance 单元测试
 */
class LeastActiveLoadBalanceTest {

    private LeastActiveLoadBalance<String> lb;
    private Request request;

    @BeforeEach
    void setUp() {
        lb = new LeastActiveLoadBalance<>();
        request = mockRequest();
    }

    @Test
    void selectShouldPickLeastActiveReference() {
        /* A=10, B=1, C=5 — B 活跃数最少，应被选中 */
        TestReference refA = new TestReference("A", 10);
        TestReference refB = new TestReference("B", 1);
        TestReference refC = new TestReference("C", 5);
        List<Reference<String>> refs = new ArrayList<>(List.of(refA, refB, refC));
        lb.onRefresh(refs);

        /*
         * 由于起始 index 是随机的，单次 select 不一定选 B。
         * 但 B 的 activeCount 最小，只要被扫描到就一定会胜出。
         * 多次调用，B 应被选中至少一次。
         */
        boolean bSelected = false;
        for (int i = 0; i < 200; i++) {
            Reference<String> selected = lb.select(request);
            if ("B".equals(((TestReference) selected).getName())) {
                bSelected = true;
                break;
            }
        }
        assertTrue(bSelected, "活跃数最少的 B 应至少被选中一次");
    }

    @Test
    void selectShouldNeverPickMostActiveWhenLessActiveExists() {
        /* A=100, B=1 — 只要 B 被扫描到就会选 B 而非 A */
        TestReference refA = new TestReference("A", 100);
        TestReference refB = new TestReference("B", 1);
        List<Reference<String>> refs = new ArrayList<>(List.of(refA, refB));
        lb.onRefresh(refs);

        for (int i = 0; i < 200; i++) {
            Reference<String> selected = lb.select(request);
            String name = ((TestReference) selected).getName();
            /* A 的 activeCount 远大于 B，只有当扫描窗口只包含 A 时才可能选 A，
             * 但 MAX_REFERENCE_COUNT=10 > 2 个 reference，所以 B 总会被扫描到 */
            assertEquals("B", name, "活跃数最少的 B 应始终被选中");
        }
    }

    @Test
    void selectShouldSkipUnavailableReference() {
        TestReference refA = new TestReference("A", 0);
        TestReference refB = new TestReference("B", 1);
        refA.setAvailable(false);
        List<Reference<String>> refs = new ArrayList<>(List.of(refA, refB));
        lb.onRefresh(refs);

        for (int i = 0; i < 100; i++) {
            Reference<String> selected = lb.select(request);
            assertEquals("B", ((TestReference) selected).getName());
        }
    }

    @Test
    void selectSingleReference() {
        List<Reference<String>> refs = new ArrayList<>(List.of(new TestReference("only", 5)));
        lb.onRefresh(refs);

        Reference<String> selected = lb.select(request);
        assertEquals("only", ((TestReference) selected).getName());
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
