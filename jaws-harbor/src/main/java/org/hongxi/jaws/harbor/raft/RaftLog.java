package org.hongxi.jaws.harbor.raft;

import java.util.ArrayList;
import java.util.List;

/**
 * The in-memory Raft log: a dense suffix of entries plus the snapshot boundary
 * ({@code lastIncludedIndex}/{@code lastIncludedTerm}) that stands in for every
 * entry compacted away. Indexes are 1-based; index 0 is the empty-log origin
 * with term 0, exactly as in the paper.
 * <p>
 * NOT thread-safe by design: every access happens on the single raft thread
 * (or during startup recovery before that thread serves anything), which is
 * the same confinement {@link RaftNode} relies on for all of its state.
 *
 * @author shenhongxi
 */
public class RaftLog {

    /** Entries strictly after {@link #lastIncludedIndex}, in index order. */
    private final List<LogEntry> entries = new ArrayList<>();

    private long lastIncludedIndex;
    private long lastIncludedTerm;

    public long lastIncludedIndex() {
        return lastIncludedIndex;
    }

    public long lastIncludedTerm() {
        return lastIncludedTerm;
    }

    public long lastIndex() {
        return entries.isEmpty() ? lastIncludedIndex : entries.get(entries.size() - 1).index();
    }

    public long lastTerm() {
        return entries.isEmpty() ? lastIncludedTerm : entries.get(entries.size() - 1).term();
    }

    /**
     * Term of the entry at {@code index}, or {@code -1} when the log knows
     * nothing about it. The snapshot boundary answers for its own index, so a
     * prev-log check against a compacted prefix still succeeds.
     */
    public long termAt(long index) {
        if (index == lastIncludedIndex) {
            return lastIncludedTerm;
        }
        LogEntry entry = entryAt(index);
        return entry == null ? -1 : entry.term();
    }

    /** @return the entry at {@code index}, or null when out of the retained range. */
    public LogEntry entryAt(long index) {
        long offset = index - lastIncludedIndex - 1;
        if (offset < 0 || offset >= entries.size()) {
            return null;
        }
        return entries.get((int) offset);
    }

    /** @return up to {@code limit} entries starting at {@code fromIndex} (inclusive). */
    public List<LogEntry> from(long fromIndex, int limit) {
        List<LogEntry> result = new ArrayList<>();
        for (long index = fromIndex; index <= lastIndex() && result.size() < limit; index++) {
            LogEntry entry = entryAt(index);
            if (entry != null) {
                result.add(entry);
            }
        }
        return result;
    }

    public List<LogEntry> allEntries() {
        return List.copyOf(entries);
    }

    /** Leader-side append of one freshly created entry; no conflict is possible. */
    public void appendLocal(LogEntry entry) {
        entries.add(entry);
    }

    /** Recovery-side append of persisted entries, already known to be dense. */
    public void appendRecovered(List<LogEntry> recovered) {
        entries.addAll(recovered);
    }

    /**
     * Follower-side append with the Log Matching Property enforcement: an
     * incoming entry that conflicts with an existing one at the same index
     * truncates that entry and everything after it (paper §5.3). Entries already
     * present with the same term are kept, so a re-sent suffix is idempotent.
     *
     * @return the entries actually appended (empty when everything was a duplicate)
     */
    public List<LogEntry> appendFollower(List<LogEntry> incoming) {
        List<LogEntry> appended = new ArrayList<>();
        for (LogEntry each : incoming) {
            LogEntry existing = entryAt(each.index());
            if (existing != null) {
                if (existing.term() == each.term()) {
                    continue;
                }
                // Conflict: drop the divergent suffix before taking the leader's version.
                truncateFrom(each.index());
            }
            entries.add(each);
            appended.add(each);
        }
        return appended;
    }

    /** Whether {@link #appendFollower} would cut anything — drives log-file rewrite. */
    public boolean conflicts(List<LogEntry> incoming) {
        for (LogEntry each : incoming) {
            LogEntry existing = entryAt(each.index());
            if (existing != null && existing.term() != each.term()) {
                return true;
            }
        }
        return false;
    }

    private void truncateFrom(long index) {
        long offset = index - lastIncludedIndex - 1;
        if (offset < 0) {
            offset = 0;
        }
        if (offset < entries.size()) {
            entries.subList((int) offset, entries.size()).clear();
        }
    }

    /**
     * Compact the prefix through {@code index} into the snapshot boundary.
     * The caller has already persisted a state-machine snapshot at that point.
     */
    public void truncatePrefix(long index, long term) {
        long offset = index - lastIncludedIndex;
        if (offset > 0) {
            entries.subList(0, (int) Math.min(offset, entries.size())).clear();
        }
        lastIncludedIndex = index;
        lastIncludedTerm = term;
    }

    /**
     * Replace the whole log with a snapshot boundary (InstallSnapshot, or
     * recovery loading a snapshot). Everything previously retained is gone.
     */
    public void reset(long snapshotIndex, long snapshotTerm) {
        entries.clear();
        lastIncludedIndex = snapshotIndex;
        lastIncludedTerm = snapshotTerm;
    }
}