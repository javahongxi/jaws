package org.hongxi.jaws.cluster.loadbalance;

import org.hongxi.jaws.exception.JawsServiceException;
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
 * RandomLoadBalance 单元测试
 */
class RandomLoadBalanceTest {

    private RandomLoadBalance<String> lb;
    private Request request;

    @BeforeEach
    void setUp() {
        lb = new RandomLoadBalance<>();
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
    void selectShouldHitAllReferences() {
        List<Reference<String>> refs = createRefs("A", "B", "C");
        lb.onRefresh(refs);

        Set<String> hit = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            hit.add(((TestReference) lb.select(request)).getName());
        }
        assertEquals(3, hit.size(), "随机策略在足够次数下应命中所有 reference");
    }

    @Test
    void selectShouldSkipUnavailableReference() {
        List<Reference<String>> refs = createRefs("A", "B", "C");
        ((TestReference) refs.get(0)).setAvailable(false);
        lb.onRefresh(refs);

        for (int i = 0; i < 100; i++) {
            Reference<String> selected = lb.select(request);
            assertNotEquals("A", ((TestReference) selected).getName());
        }
    }

    @Test
    void selectShouldThrowWhenAllUnavailable() {
        List<Reference<String>> refs = createRefs("A", "B");
        refs.forEach(r -> ((TestReference) r).setAvailable(false));
        lb.onRefresh(refs);

        assertThrows(JawsServiceException.class, () -> lb.select(request));
    }

    @Test
    void selectSingleReference() {
        List<Reference<String>> refs = createRefs("only");
        lb.onRefresh(refs);

        Reference<String> selected = lb.select(request);
        assertEquals("only", ((TestReference) selected).getName());
    }

    @Test
    void selectShouldThrowWhenEmptyList() {
        lb.onRefresh(new ArrayList<>());
        assertThrows(JawsServiceException.class, () -> lb.select(request));
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
