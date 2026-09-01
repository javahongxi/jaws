package org.hongxi.jaws.cluster.loadbalance;

import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.Request;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ShortestResponseLoadBalance
 */
class ShortestResponseLoadBalanceTest {

    private ShortestResponseLoadBalance<String> lb;
    private Request request;

    @BeforeEach
    void setUp() {
        lb = new ShortestResponseLoadBalance<>();
        request = mockRequest();
    }

    @Test
    void selectShouldReturnAvailableReference() {
        lb.onRefresh(createRefs("A", "B", "C"));

        Set<String> hit = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            Reference<String> selected = lb.select(request);
            assertTrue(selected.isAvailable());
            hit.add(((TestReference) selected).getName());
        }
        assertEquals(3, hit.size(), "with equal estimated response time, all references should be hit by weight/random");
    }

    @Test
    void selectShouldPreferLessLoadedReference() {
        TestReference busy = new TestReference("busy", 100);
        TestReference idle = new TestReference("idle", 0);
        List<Reference<String>> refs = new ArrayList<>();
        refs.add(busy);
        refs.add(idle);
        lb.onRefresh(refs);

        int idleHits = 0;
        for (int i = 0; i < 200; i++) {
            if ("idle".equals(((TestReference) lb.select(request)).getName())) {
                idleHits++;
            }
        }
        assertTrue(idleHits > 100, "the less loaded reference should receive more traffic, idleHits=" + idleHits);
    }

    @Test
    void onRefreshShouldDiscardStaleStatistics() throws Exception {
        lb.onRefresh(createRefs("A", "B"));
        for (int i = 0; i < 50; i++) {
            lb.select(request);
        }
        assertEquals(Set.of("A", "B"), slideWindowNames());

        lb.onRefresh(createRefs("A", "C"));
        for (int i = 0; i < 50; i++) {
            lb.select(request);
        }
        assertEquals(Set.of("A", "C"), slideWindowNames(),
                "refresh should discard statistics of removed references and keep existing ones");
    }

    private Set<String> slideWindowNames() throws Exception {
        Field field = AbstractLoadBalance.class.getDeclaredField("estimators");
        field.setAccessible(true);
        Set<String> names = new HashSet<>();
        for (Object key : ((Map<?, ?>) field.get(lb)).keySet()) {
            names.add(((TestReference) key).getName());
        }
        return names;
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
