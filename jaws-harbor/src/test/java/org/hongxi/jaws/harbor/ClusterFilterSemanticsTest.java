package org.hongxi.jaws.harbor;

import com.alibaba.nacos.api.naming.pojo.Instance;
import org.hongxi.jaws.harbor.client.HarborClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the {@code cluster} and {@code healthyOnly} parameters actually decide.
 * <p>
 * Both were once accepted and dropped, which is worse than rejecting them: a
 * caller that asked for one cluster and received all of them has no way to
 * notice. Each assertion here is about a visible difference, and the data half is
 * checked from a real nacos-client as well.
 *
 * @author shenhongxi
 */
@Timeout(90)
class ClusterFilterSemanticsTest extends HarborRegistryFixture {

    @Test
    void queryHonoursClusterAllowListAndDropsDisabledInstances() throws Exception {
        String service = "cluster-query";
        register(service, "127.0.0.1", 9001, "a", true);
        register(service, "127.0.0.1", 9002, "a", false);
        register(service, "127.0.0.1", 9003, "b", true);

        HarborClient reader = newClient();
        awaitTrue(() -> reader.getInstances(service, GROUP, false).size() == 2, 5_000);

        assertEquals(2, portsOf(reader.getInstances(service, GROUP, null, false)).size(),
                "no cluster asked for: both clusters, but the disabled instance is out");
        assertFalse(portsOf(reader.getInstances(service, GROUP, null, false)).contains(9002),
                "enableOnly is applied on a query, as in Nacos");

        assertEquals(List.of(9001),
                portsOf(reader.getInstances(service, GROUP, "a", false)), "single cluster");
        assertEquals(List.of(9001, 9003),
                portsOf(reader.getInstances(service, GROUP, "a,b", false)).stream()
                        .sorted().toList(), "a cluster allow-list is a union");
        assertEquals(List.of(), portsOf(reader.getInstances(service, GROUP, "c", false)),
                "an unknown cluster answers empty, not everything");

        // Seen from the other implementation: nacos-client's own selectInstances
        // filters locally, so the plain query is what proves the server side —
        // both clusters present, the disabled one gone.
        awaitTrue(() -> nacosQuery(service).size() == 2, 5_000);
        assertEquals(List.of(9001, 9003),
                nacosQuery(service).stream().map(Instance::getPort).sorted().toList());
        assertEquals(1, nacosSelectInCluster(service, "a", true).size(),
                "nacos-client's own view of the same data filters to one instance");
    }

    @Test
    void subscribeReplyAndPushesAreNarrowedPerSubscriber() throws Exception {
        String service = "cluster-subscribe";
        register(service, "127.0.0.1", 9101, "a", true);

        var seen = new CopyOnWriteArrayList<org.hongxi.jaws.harbor.model.ServiceInfo>();
        HarborClient watcher = newClient();
        watcher.subscribe(service, GROUP, "a", seen::add);

        awaitTrue(() -> !seen.isEmpty(), 5_000);
        assertEquals(List.of(9101), hostsOf(seen.get(seen.size() - 1)),
                "the subscribe reply is already cluster-filtered");

        // A change in a cluster this watcher did not ask for must not reach it.
        register(service, "127.0.0.1", 9102, "b", true);
        // Past the 500ms push coalescing window: a leaking push would be in by now.
        Thread.sleep(1_200);
        assertTrue(seen.stream().allMatch(each -> hostsOf(each).contains(9101)
                        && !hostsOf(each).contains(9102)),
                "a push leaked an instance from a cluster outside the filter");

        // A change inside the filter does reach it.
        register(service, "127.0.0.1", 9103, "a", true);
        awaitTrue(() -> seen.stream().anyMatch(each -> hostsOf(each).contains(9103)), 10_000);
    }

    @Test
    void instanceKeepsTheClusterItWasRegisteredIn() throws Exception {
        String service = "cluster-roundtrip";
        register(service, "127.0.0.1", 9201, "east", true);

        HarborClient reader = newClient();
        awaitTrue(() -> reader.getInstances(service, GROUP, null, false).size() == 1, 5_000);
        assertEquals("east",
                reader.getInstances(service, GROUP, null, false).get(0).getClusterName(),
                "clusterName survived both the registration and the read projection");
        assertEquals("east", nacosQuery(service).get(0).getClusterName(),
                "and is visible to a nacos-client");
    }

    @Test
    void unclusteredRegistrationFallsIntoTheDefaultCluster() throws Exception {
        // A client that names no cluster gets "DEFAULT", as Nacos's own client fills
        // it in — otherwise a filter for DEFAULT would answer nothing.
        HarborClient provider = newClient();
        org.hongxi.jaws.harbor.model.Instance instance =
                new org.hongxi.jaws.harbor.model.Instance();
        instance.setIp("127.0.0.1");
        instance.setPort(9301);
        provider.registerInstance("cluster-default", instance);

        awaitTrue(() -> provider.getInstances("cluster-default", GROUP, "DEFAULT", false)
                .size() == 1, 5_000);
    }
}
