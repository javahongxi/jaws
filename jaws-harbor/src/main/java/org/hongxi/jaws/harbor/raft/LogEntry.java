package org.hongxi.jaws.harbor.raft;

import org.hongxi.jaws.harbor.model.RaftEntry;

import java.util.Base64;

/**
 * One entry of the replicated Raft log: a position ({@code index}), the
 * {@code term} of the leader that created it, and the opaque state-machine
 * command ({@code data}, a serialized {@link NamingCommand}).
 * <p>
 * Immutable and confined to the raft thread (plus startup recovery), so no
 * defensive copying is needed.
 *
 * @author shenhongxi
 */
public record LogEntry(long index, long term, byte[] data) {

    /** Wire form: command bytes travel as Base64 inside the JSON envelope. */
    public RaftEntry toDto() {
        return new RaftEntry(index, term, Base64.getEncoder().encodeToString(data));
    }

    public static LogEntry fromDto(RaftEntry dto) {
        return new LogEntry(dto.getIndex(), dto.getTerm(),
                Base64.getDecoder().decode(dto.getData()));
    }
}