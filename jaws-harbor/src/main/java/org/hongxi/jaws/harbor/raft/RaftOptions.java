package org.hongxi.jaws.harbor.raft;

/**
 * Timing and compaction knobs of a {@link RaftNode}. The defaults follow the
 * paper's guidance that the election timeout must dwarf the heartbeat interval
 * (here 1000–2000ms randomized vs 300ms), so a follower rarely mistakes a busy
 * leader for a dead one; tests shrink them to keep elections fast.
 *
 * @param electionTimeoutMinMs lower bound of the randomized election timeout
 * @param electionTimeoutMaxMs upper bound of the randomized election timeout
 * @param heartbeatMs          leader AppendEntries interval
 * @param snapshotThreshold    applied entries since the last snapshot that trigger compaction
 * @author shenhongxi
 */
public record RaftOptions(long electionTimeoutMinMs, long electionTimeoutMaxMs,
                          long heartbeatMs, int snapshotThreshold) {

    public static RaftOptions defaults() {
        return new RaftOptions(1000, 2000, 300, 10_000);
    }
}