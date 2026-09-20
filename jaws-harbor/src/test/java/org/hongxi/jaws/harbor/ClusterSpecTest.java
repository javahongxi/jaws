package org.hongxi.jaws.harbor;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The URL-param shape a {@link HarborServer} auto-joins from: same
 * {@code host:port,host:port} string Nacos calls {@code serverAddr}, with the
 * trim / blank / null handling that {@link ClusterSpec#fromServerAddr} owns.
 *
 * @author shenhongxi
 */
class ClusterSpecTest {

    @Test
    void splitsTrimsAndDropsBlankEntries() {
        ClusterSpec spec = ClusterSpec.fromServerAddr(
                " 10.0.0.1:19848 ,, 10.0.0.2:19848 , ");
        assertEquals(List.of("10.0.0.1:19848", "10.0.0.2:19848"), spec.serverList());
        assertFalse(spec.isEmpty());
    }

    @Test
    void nullAndEmptyAreValidSingleNodeBootstrap() {
        assertTrue(ClusterSpec.fromServerAddr(null).isEmpty());
        assertTrue(ClusterSpec.fromServerAddr("").isEmpty());
        // A parameter present but blank (e.g. unset sys-prop) is not an error.
        assertTrue(ClusterSpec.fromServerAddr("  ").isEmpty());
    }

    @Test
    void preservesOrderingBecauseTheFirstEntryIsThePrimary() {
        ClusterSpec spec = ClusterSpec.fromServerAddr(
                "10.0.0.3:1,10.0.0.1:2,10.0.0.2:3");
        assertEquals("10.0.0.3:1", spec.serverList().get(0));
    }

    @Test
    void serverListIsDefensivelyCopied() {
        // Mutating the source list after construction must not leak in.
        ArrayList<String> mutable = new ArrayList<>();
        mutable.add("10.0.0.1:19848");
        ClusterSpec spec = new ClusterSpec(mutable);
        mutable.add("10.0.0.2:19848");
        assertEquals(1, spec.serverList().size());
        assertThrows(UnsupportedOperationException.class,
                () -> spec.serverList().add("late"));
    }
}
