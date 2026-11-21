package org.hongxi.jaws.metadata;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.rpc.URL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Publish/history semantics of the provider-side {@link ApplicationMetadataManager}:
 * every mutation recomputes the revision, old revisions stay fetchable for consumers
 * holding stale instance snapshots, and the last unexport empties the application.
 */
class ApplicationMetadataManagerTest {

    private static final String APP = "app-a";

    @BeforeEach
    void setUp() {
        ApplicationMetadataManager.clear();
    }

    private URL serviceUrl(String iface) {
        Map<String, String> params = new HashMap<>();
        params.put(UrlParam.Identity.ENDPOINT_TYPE.getName(), JawsConstants.ENDPOINT_TYPE_SERVICE);
        return new URL("jaws", "10.0.0.1", 8080, iface, params);
    }

    @Test
    void addPublishesCurrentRevision() {
        URL demo = serviceUrl("com.example.DemoService");
        String revision = ApplicationMetadataManager.addServiceUrl(APP, demo);

        assertTrue(ApplicationMetadataManager.hasServices(APP));
        MetadataInfo current = ApplicationMetadataManager.getMetadataInfo(APP, revision);
        assertEquals(revision, current.getRevision());
        assertEquals(1, current.getServices().size());
    }

    @Test
    void secondAddRecomputesRevisionAndKeepsHistory() {
        String r1 = ApplicationMetadataManager.addServiceUrl(APP, serviceUrl("com.example.DemoService"));
        String r2 = ApplicationMetadataManager.addServiceUrl(APP, serviceUrl("com.example.OrderService"));
        assertNotEquals(r1, r2);

        MetadataInfo current = ApplicationMetadataManager.getMetadataInfo(APP, r2);
        assertEquals(2, current.getServices().size());

        MetadataInfo old = ApplicationMetadataManager.getMetadataInfo(APP, r1);
        assertEquals(1, old.getServices().size(),
                "consumers holding the old revision can still fetch it during rolling update");
    }

    @Test
    void removeRecomputesRevision() {
        URL demo = serviceUrl("com.example.DemoService");
        URL order = serviceUrl("com.example.OrderService");
        ApplicationMetadataManager.addServiceUrl(APP, demo);
        String r2 = ApplicationMetadataManager.addServiceUrl(APP, order);

        String r3 = ApplicationMetadataManager.removeServiceUrl(APP, demo);
        assertNotEquals(r2, r3);
        assertEquals(1, ApplicationMetadataManager.getMetadataInfo(APP, r3).getServices().size());
        assertEquals(2, ApplicationMetadataManager.getMetadataInfo(APP, r2).getServices().size());
    }

    @Test
    void lastRemoveEmptiesApplicationButKeepsHistory() {
        URL demo = serviceUrl("com.example.DemoService");
        String r1 = ApplicationMetadataManager.addServiceUrl(APP, demo);

        assertNull(ApplicationMetadataManager.removeServiceUrl(APP, demo),
                "null signals the caller to deregister the application instance");
        assertFalse(ApplicationMetadataManager.hasServices(APP));
        assertEquals(1, ApplicationMetadataManager.getMetadataInfo(APP, r1).getServices().size(),
                "the last revision stays fetchable from history");
    }

    @Test
    void unknownApplicationOrRevisionReturnsNull() {
        assertNull(ApplicationMetadataManager.getMetadataInfo("no-such-app", "whatever"));
        ApplicationMetadataManager.addServiceUrl(APP, serviceUrl("com.example.DemoService"));
        assertNull(ApplicationMetadataManager.getMetadataInfo(APP, "no-such-revision"));
        assertNull(ApplicationMetadataManager.removeServiceUrl("no-such-app", serviceUrl("com.example.DemoService")));
    }
}