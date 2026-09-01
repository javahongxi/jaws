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
 * Unit tests for RoundRobinLoadBalance
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

        /* enough invocations to ensure every reference is selected */
        Set<String> hit = new HashSet<>();
        for (int i = 0; i < 300; i++) {
            hit.add(((TestReference) lb.select(request)).getName());
        }
        assertEquals(3, hit.size(), "round-robin should hit all references");
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

        /* round-robin should distribute evenly, about 100 each */
        for (int count : counts) {
            assertEquals(100, count, "round-robin should distribute invocations evenly");
        }
    }

    @Test
    void selectShouldSkipUnavailableReference() {
        List<Reference<String>> refs = createRefs("A", "B", "C");
        /* mark the first created reference as unavailable */
        ((TestReference) refs.get(0)).setAvailable(false);
        lb.onRefresh(refs);

        for (int i = 0; i < 100; i++) {
            Reference<String> selected = lb.select(request);
            assertNotEquals("A", ((TestReference) selected).getName());
        }
    }

    @Test
    void selectShouldInterleaveSmoothly() {
        List<Reference<String>> refs = createRefs("A", "B");
        lb.onRefresh(refs);

        /* with smooth weighted round-robin, two equal-weight nodes should strictly alternate with no consecutive hits */
        String previous = null;
        for (int i = 0; i < 100; i++) {
            String name = ((TestReference) lb.select(request)).getName();
            assertNotEquals(previous, name, "smooth round-robin should not hit the same reference consecutively");
            previous = name;
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
