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
     * {@link NamingCommand} or {@link ConfigCommand}; a NOOP entry carries no
     * state change.
     *
     * @return a deterministic refusal message when the command was rejected
     *         at apply time (a lost CAS), or {@code null} when it applied.
     *         The value travels back to the proposer, so a refusal is an
     *         answer — not an exception that would break the apply loop.
     */
    String onApply(LogEntry entry);

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