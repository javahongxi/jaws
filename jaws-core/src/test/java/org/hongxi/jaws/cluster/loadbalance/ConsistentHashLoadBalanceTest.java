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
 * ConsistentHashLoadBalance 单元测试
 */
class ConsistentHashLoadBalanceTest {

    private ConsistentHashLoadBalance<String> lb;

    @BeforeEach
    void setUp() {
        lb = new ConsistentHashLoadBalance<>();
    }

    @Test
    void sameArgumentsShouldAlwaysSelectSameReference() {
        List<Reference<String>> refs = createRefs("A", "B", "C");
        lb.onRefresh(refs);

        Request request = mockRequestWithArgs("hello", "world");
        Reference<String> first = lb.select(request);

        /* 相同参数应始终哈希到同一个 reference */
        for (int i = 0; i < 100; i++) {
            Reference<String> selected = lb.select(request);
            assertSame(first, selected, "相同参数的请求应始终选中同一个 reference");
        }
    }

    @Test
    void differentArgumentsMaySelectDifferentReferences() {
        List<Reference<String>> refs = createRefs("A", "B", "C");
        lb.onRefresh(refs);

        Set<Reference<String>> selected = new HashSet<>();
        /* 用大量不同的参数，验证哈希分布能命中多个 reference */
        for (int i = 0; i < 1000; i++) {
            Request request = mockRequestWithArgs("arg-" + i);
            selected.add(lb.select(request));
        }
        assertTrue(selected.size() > 1, "不同参数应能哈希到不同的 reference");
    }

    @Test
    void selectShouldReturnAvailableReference() {
        List<Reference<String>> refs = createRefs("A", "B", "C");
        lb.onRefresh(refs);

        Request request = mockRequestWithArgs("test");
        for (int i = 0; i < 100; i++) {
            Reference<String> selected = lb.select(request);
            assertTrue(selected.isAvailable());
        }
    }

    @Test
    void selectShouldFallbackWhenPreferredUnavailable() {
        List<Reference<String>> refs = createRefs("A", "B", "C");
        lb.onRefresh(refs);

        /* 先确定某参数哈希到哪个 reference */
        Request request = mockRequestWithArgs("fallback-test");
        Reference<String> preferred = lb.select(request);

        /* 将该 reference 标记为不可用 */
        ((TestReference) preferred).setAvailable(false);

        /* 相同参数应选中另一个可用的 reference */
        Reference<String> fallback = lb.select(request);
        assertNotSame(preferred, fallback, "首选 reference 不可用时应选中其他 reference");
        assertTrue(fallback.isAvailable());
    }

    @Test
    void selectSingleReference() {
        List<Reference<String>> refs = createRefs("only");
        lb.onRefresh(refs);

        Request request = mockRequestWithArgs("test");
        Reference<String> selected = lb.select(request);
        assertEquals("only", ((TestReference) selected).getName());
    }

    @Test
    void selectWithoutArgumentsShouldUseRequestHashCode() {
        List<Reference<String>> refs = createRefs("A", "B", "C");
        lb.onRefresh(refs);

        /* arguments 为 null 时使用 request 自身的 hashCode */
        Request request = mockRequestWithArgs((Object[]) null);
        Reference<String> first = lb.select(request);

        /* 同一个 request 对象应返回相同结果 */
        for (int i = 0; i < 50; i++) {
            assertSame(first, lb.select(request));
        }
    }

    private List<Reference<String>> createRefs(String... names) {
        List<Reference<String>> list = new ArrayList<>();
        for (String name : names) {
            list.add(new TestReference(name));
        }
        return list;
    }

    private Request mockRequestWithArgs(Object... args) {
        return new Request() {
            @Override public String getInterfaceName() { return "TestService"; }
            @Override public String getMethodName() { return "test"; }
            @Override public String getParametersDesc() { return ""; }
            @Override public Object[] getArguments() { return args; }
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
