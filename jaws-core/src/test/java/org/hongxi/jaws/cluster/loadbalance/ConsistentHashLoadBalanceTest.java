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
 * Unit tests for ConsistentHashLoadBalance
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

        /* same arguments should always hash to the same reference */
        for (int i = 0; i < 100; i++) {
            Reference<String> selected = lb.select(request);
            assertSame(first, selected, "requests with the same arguments should always select the same reference");
        }
    }

    @Test
    void differentArgumentsMaySelectDifferentReferences() {
        List<Reference<String>> refs = createRefs("A", "B", "C");
        lb.onRefresh(refs);

        Set<Reference<String>> selected = new HashSet<>();
        /* use a large number of distinct arguments to verify the hash distribution hits multiple references */
        for (int i = 0; i < 1000; i++) {
            Request request = mockRequestWithArgs("arg-" + i);
            selected.add(lb.select(request));
        }
        assertTrue(selected.size() > 1, "different arguments should hash to different references");
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

        /* first determine which reference a given argument hashes to */
        Request request = mockRequestWithArgs("fallback-test");
        Reference<String> preferred = lb.select(request);

        /* mark that reference as unavailable */
        ((TestReference) preferred).setAvailable(false);

        /* the same arguments should select another available reference */
        Reference<String> fallback = lb.select(request);
        assertNotSame(preferred, fallback, "when the preferred reference is unavailable, another reference should be selected");
        assertTrue(fallback.isAvailable());
    }

    @Test
    void removingProviderShouldKeepMostMappings() {
        /* note: onRefresh shuffles the input list in place, so keep original object references instead of indexing */
        TestReference a = new TestReference("A");
        TestReference b = new TestReference("B");
        TestReference c = new TestReference("C");
        List<Reference<String>> refs = new ArrayList<>();
        refs.add(a);
        refs.add(b);
        refs.add(c);
        lb.onRefresh(refs);

        /* record which reference each request key hits */
        Reference<String>[] before = new Reference[200];
        for (int i = 0; i < 200; i++) {
            before[i] = lb.select(mockRequestWithArgs("key-" + i));
        }

        /* after removing B, keys that originally hit A/C should all remain unchanged (core property of consistent hashing) */
        List<Reference<String>> remaining = new ArrayList<>();
        remaining.add(a);
        remaining.add(c);
        lb.onRefresh(remaining);

        for (int i = 0; i < 200; i++) {
            if ("B".equals(((TestReference) before[i]).getName())) {
                continue;
            }
            assertSame(before[i], lb.select(mockRequestWithArgs("key-" + i)),
                    "request keys served by non-removed nodes should not be remapped");
        }
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

        /* when arguments is null, the request's own hashCode is used */
        Request request = mockRequestWithArgs((Object[]) null);
        Reference<String> first = lb.select(request);

        /* the same request object should return the same result */
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
            @Override public String getParamDesc() { return ""; }
            @Override public Object[] getArguments() { return args; }
            @Override public java.util.Map<String, String> getAttachments() { return null; }
            @Override public void setAttachment(String name, String value) {}
            @Override public long getRequestId() { return 0; }
            @Override public int getRetries() { return 0; }
            @Override public byte getSerializationNumber() { return 0; }
        };
    }
}
