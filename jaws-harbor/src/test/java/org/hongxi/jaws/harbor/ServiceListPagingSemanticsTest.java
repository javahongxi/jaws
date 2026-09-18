package org.hongxi.jaws.harbor;

import org.hongxi.jaws.harbor.client.HarborClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@code pageNo} / {@code pageSize} decide on a service list.
 * <p>
 * Paging used to be ignored, so a client asking for two names received all of
 * them and had no way to tell a page from a truncation. The split between the
 * page it got and the size of the whole match set is the point.
 *
 * @author shenhongxi
 */
@Timeout(60)
class ServiceListPagingSemanticsTest extends HarborRegistryFixture {

    @Test
    void pagesAreTrimmedWhileCountReportsTheWholeMatchSet() throws Exception {
        // Its own group: paging counts are per group, and a neighbouring class
        // leaves services behind in DEFAULT_GROUP.
        String group = "PAGING_GROUP";
        String servicePrefix = "paging-";
        HarborClient reader = newClient();
        for (int i = 0; i < 3; i++) {
            register(servicePrefix + i, group, "127.0.0.1", 9400 + i, "a", true);
        }
        awaitTrue(() -> reader.listServices(group).size() == 3, 5_000);

        var first = reader.listServicesPage(group, 1, 2);
        assertEquals(2, first.names().size(), "page size is honoured");
        assertEquals(3, first.total(), "count is the whole match set, not the page");
        assertTrue(first.hasNextPage());

        var second = reader.listServicesPage(group, 2, 2);
        assertEquals(1, second.names().size(), "the tail page is trimmed, not padded");
        assertEquals(3, second.total());
        assertFalse(second.hasNextPage(), "an incomplete page is not necessarily a next one");

        var beyond = reader.listServicesPage(group, 9, 2);
        assertEquals(0, beyond.names().size(), "a start past the end answers empty");
        assertEquals(3, beyond.total(), "the total stays truthful on an empty page");
    }
}
