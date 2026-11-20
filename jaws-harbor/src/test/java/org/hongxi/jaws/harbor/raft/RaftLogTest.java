package org.hongxi.jaws.harbor.raft;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link RaftLog}: the Log Matching Property mechanics —
 * conflict truncation, idempotent re-append, and the snapshot boundary that
 * stands in for a compacted prefix.
 *
 * @author shenhongxi
 */
class RaftLogTest {

    private static LogEntry entry(long index, long term) {
        return new LogEntry(index, term, ("cmd-" + index).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void emptyLogStartsAtTheOrigin() {
        RaftLog log = new RaftLog();
        assertEquals(0, log.lastIndex());
        assertEquals(0, log.lastTerm());
        assertEquals(0, log.termAt(0));
        assertEquals(-1, log.termAt(1));
        assertNull(log.entryAt(1));
    }

    @Test
    void followerAppendKeepsMatchingEntriesAndTruncatesConflicts() {
        RaftLog log = new RaftLog();
        log.appendFollower(List.of(entry(1, 1), entry(2, 1), entry(3, 1)));

        // Re-sent prefix with the same terms is a no-op, a divergent suffix is cut.
        List<LogEntry> appended = log.appendFollower(List.of(entry(2, 1), entry(3, 2), entry(4, 2)));
        assertEquals(2, appended.size(), "only the new leader's versions are appended");
        assertEquals(4, log.lastIndex());
        assertEquals(2, log.termAt(3));
        assertEquals(2, log.termAt(4));
    }

    @Test
    void conflictsPredictsTheTruncationWithoutMutating() {
        RaftLog log = new RaftLog();
        log.appendFollower(List.of(entry(1, 1), entry(2, 1)));
        assertTrue(log.conflicts(List.of(entry(2, 2))));
        assertFalse(log.conflicts(List.of(entry(2, 1), entry(3, 1))));
        assertEquals(2, log.lastIndex(), "the prediction must not have touched the log");
    }

    @Test
    void snapshotBoundaryAnswersForItsOwnIndex() {
        RaftLog log = new RaftLog();
        log.appendFollower(List.of(entry(1, 1), entry(2, 1), entry(3, 2)));
        log.truncatePrefix(2, 1);

        assertEquals(2, log.lastIncludedIndex());
        assertEquals(1, log.lastIncludedTerm());
        assertEquals(1, log.termAt(2), "the boundary answers the prev-log check");
        assertNull(log.entryAt(1), "compacted entries are gone");
        assertNull(log.entryAt(2));
        assertEquals(3, log.lastIndex());
        List<LogEntry> suffix = log.from(3, 10);
        assertEquals(1, suffix.size());
        assertEquals(3, suffix.get(0).index());
        assertEquals(2, suffix.get(0).term());
    }

    @Test
    void resetReplacesEverythingWithTheSnapshotBoundary() {
        RaftLog log = new RaftLog();
        log.appendFollower(List.of(entry(1, 1), entry(2, 1)));
        log.reset(5, 3);

        assertEquals(5, log.lastIndex());
        assertEquals(3, log.lastTerm());
        assertEquals(3, log.termAt(5));
        assertEquals(-1, log.termAt(4), "below the boundary the log knows nothing");
        assertTrue(log.allEntries().isEmpty());
    }

    @Test
    void fromHonoursTheBatchLimit() {
        RaftLog log = new RaftLog();
        log.appendFollower(List.of(entry(1, 1), entry(2, 1), entry(3, 1), entry(4, 1)));
        assertEquals(2, log.from(2, 2).size());
        assertEquals(2, log.from(2, 2).get(0).index(), "fromIndex is inclusive");
    }
}