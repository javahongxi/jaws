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
 * AdaptiveLoadBalance 单元测试
 */
class AdaptiveLoadBalanceTest {

    private AdaptiveLoadBalance<String> lb;
    private Request request;

    @BeforeEach
    void setUp() {
        lb = new AdaptiveLoadBalance<>();
        request = mockRequest();
    }

    @Test
    void selectShouldReachAllReferences() {
        lb.onRefresh(createRefs("A", "B", "C"));

        Set<String> hit = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            Reference<String> selected = lb.select(request);
            assertTrue(selected.isAvailable());
            hit.add(((TestReference) selected).getName());
        }
        assertEquals(3, hit.size(), "估算负载相同时应按权重/随机命中所有 reference");
    }

    @Test
    void selectShouldSkipUnavailableReference() {
        TestReference offline = new TestReference("offline");
        offline.setAvailable(false);
        List<Reference<String>> refs = new ArrayList<>();
        refs.add(offline);
        refs.add(new TestReference("B"));
        refs.add(new TestReference("C"));
        lb.onRefresh(refs);

        for (int i = 0; i < 200; i++) {
            assertNotEquals("offline", ((TestReference) lb.select(request)).getName());
        }
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
        assertEquals(200, idleHits, "两个候选时 P2C 必然选中低负载的 reference");
    }

    @Test
    void selectCandidatesShouldOrderByEstimatedLoad() {
        List<Reference<String>> refs = new ArrayList<>();
        refs.add(new TestReference("high", 30));
        refs.add(new TestReference("low", 10));
        refs.add(new TestReference("mid", 20));
        lb.onRefresh(refs);

        List<Reference<String>> candidates = lb.selectCandidates(request);
        assertEquals(3, candidates.size());
        assertEquals("low", ((TestReference) candidates.get(0)).getName(),
                "候选列表应按估算负载升序，低负载优先重试");
        assertEquals("mid", ((TestReference) candidates.get(1)).getName());
        assertEquals("high", ((TestReference) candidates.get(2)).getName());
    }

    @Test
    void onRefreshShouldDiscardStaleEstimators() throws Exception {
        lb.onRefresh(createRefs("A", "B"));
        for (int i = 0; i < 50; i++) {
            lb.select(request);
        }
        assertEquals(Set.of("A", "B"), estimatorNames());

        lb.onRefresh(createRefs("A", "C"));
        for (int i = 0; i < 50; i++) {
            lb.select(request);
        }
        assertEquals(Set.of("A", "C"), estimatorNames(),
                "刷新后应清理已移除 reference 的统计数据，保留现存 reference");
    }

    private Set<String> estimatorNames() throws Exception {
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
