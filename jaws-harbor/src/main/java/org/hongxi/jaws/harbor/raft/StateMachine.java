package org.hongxi.jaws.harbor.raft;

/**
 * The replicated state machine a {@link RaftNode} drives — the harbor
 * counterpart of Nacos's {@code RequestProcessor4CP} (onApply + snapshot
 * operations). Implementations must be deterministic: every node applies the
 * same committed entries in the same order and must reach the same state.
 * <p>
 * All three methods are called on the raft thread only.
 *
 * @author shenhongxi
 */
public interface StateMachine {

    /**
     * Apply one committed log entry. The command bytes are a serialized
     * {@link NamingCommand}; a NOOP entry carries no state change.
     */
    void onApply(LogEntry entry);

    /**
     * Serialize the whole current state for compaction or InstallSnapshot.
     * Called at a stable applied point, so the image matches the log position
     * the caller records alongside it.
     */
    byte[] saveSnapshot();

    /**
     * Replace the whole state with a snapshot image (recovery or
     * InstallSnapshot). Everything previously held is discarded.
     */
    void loadSnapshot(byte[] data);
}