package org.hongxi.jaws.harbor.client;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which node a client starts on, and in what order the rest are tried.
 * <p>
 * The start is random on purpose — a hundred provider processes against a
 * three-node cluster must not all land on the first entry and only scatter once
 * it dies. That is the same reason Nacos seeds its rotation cursor randomly.
 *
 * @author shenhongxi
 */
class NodeSelectionTest {

    @Test
    void aSingleNodeListHasOnePossibleStart() {
        assertEquals(0, HarborConnection.startCursor(1));
        assertEquals(0, HarborConnection.startCursor(0));
    }

    @Test
    void theStartSpreadsOverTheList() {
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 300; i++) {
            int cursor = HarborConnection.startCursor(4);
            assertTrue(cursor >= 0 && cursor < 4, "start index out of range: " + cursor);
            seen.add(cursor);
        }
        assertEquals(4, seen.size(), "every node should be reachable as a start");
    }

    @Test
    void thePrimaryAddressIsAlsoANodeInTheList() {
        List<String> all = HarborClientConfig.of("127.0.0.1", 19848).allAddresses();
        assertEquals(List.of("127.0.0.1:19848"), all);

        List<String> cluster = HarborClientConfig
                .ofCluster("127.0.0.1:19848,127.0.0.1:19849,127.0.0.1:19850")
                .allAddresses();
        assertEquals(3, cluster.size());
        assertEquals("127.0.0.1:19848", cluster.get(0));
    }
}
