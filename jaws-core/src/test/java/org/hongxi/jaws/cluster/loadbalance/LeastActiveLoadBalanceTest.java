package org.hongxi.jaws.cluster.loadbalance;

import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.Request;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for LeastActiveLoadBalance
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
        /* A=10, B=1, C=5 — B has the least active count and should be selected */
        TestReference refA = new TestReference("A", 10);
        TestReference refB = new TestReference("B", 1);
        TestReference refC = new TestReference("C", 5);
        List<Reference<String>> refs = new ArrayList<>(List.of(refA, refB, refC));
        lb.onRefresh(refs);

        /*
         * Since the starting index is random, a single select may not pick B.
         * But B has the smallest activeCount and always wins once scanned.
         * Over multiple invocations, B should be selected at least once.
         */
        boolean bSelected = false;
        for (int i = 0; i < 200; i++) {
            Reference<String> selected = lb.select(request);
            if ("B".equals(((TestReference) selected).getName())) {
                bSelected = true;
                break;
            }
        }
        assertTrue(bSelected, "B with the least active count should be selected at least once");
    }

    @Test
    void selectShouldNeverPickMostActiveWhenLessActiveExists() {
        /* A=100, B=1 — B wins over A whenever B is scanned */
        TestReference refA = new TestReference("A", 100);
        TestReference refB = new TestReference("B", 1);
        List<Reference<String>> refs = new ArrayList<>(List.of(refA, refB));
        lb.onRefresh(refs);

        for (int i = 0; i < 200; i++) {
            Reference<String> selected = lb.select(request);
            String name = ((TestReference) selected).getName();
            /* A's activeCount is much larger than B's; A can only be picked when the scan window
             * contains A alone, but MAX_REFERENCE_COUNT=10 > 2 references, so B is always scanned */
            assertEquals("B", name, "B with the least active count should always be selected");
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
            @Override public String getParamDesc() { return ""; }
            @Override public Object[] getArguments() { return null; }
            @Override public java.util.Map<String, String> getAttachments() { return null; }
            @Override public void setAttachment(String name, String value) {}
            @Override public long getRequestId() { return 0; }
            @Override public int getRetries() { return 0; }
            @Override public byte getSerializationNumber() { return 0; }
        };
    }
}
