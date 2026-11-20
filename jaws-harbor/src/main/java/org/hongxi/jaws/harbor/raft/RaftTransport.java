package org.hongxi.jaws.harbor.raft;

import org.hongxi.jaws.harbor.model.request.RaftAppendRequest;
import org.hongxi.jaws.harbor.model.request.RaftInstallSnapshotRequest;
import org.hongxi.jaws.harbor.model.request.RaftVoteRequest;
import org.hongxi.jaws.harbor.model.request.RaftWriteForwardRequest;
import org.hongxi.jaws.harbor.model.response.RaftAppendResponse;
import org.hongxi.jaws.harbor.model.response.RaftInstallSnapshotResponse;
import org.hongxi.jaws.harbor.model.response.RaftVoteResponse;
import org.hongxi.jaws.harbor.model.response.RaftWriteForwardResponse;

import java.util.concurrent.CompletableFuture;

/**
 * Peer-to-peer transport of the Raft RPCs — the CP counterpart of
 * {@code distro.HarborNodeTransport}. Every call is asynchronous so the single
 * raft thread never blocks on the network: it fires requests and processes
 * replies as ordinary events on its own queue.
 * <p>
 * A failed or timed-out call completes the future exceptionally; the raft
 * algorithm treats that exactly like a lost packet (retry on the next
 * heartbeat / election round), so implementations need no retry logic here.
 *
 * @author shenhongxi
 */
public interface RaftTransport {

    /** RequestVote RPC (paper §5.2) toward the peer at {@code target} (host:port). */
    CompletableFuture<RaftVoteResponse> requestVote(String target, RaftVoteRequest request);

    /** AppendEntries RPC (paper §5.3), also used as the leader heartbeat. */
    CompletableFuture<RaftAppendResponse> appendEntries(String target, RaftAppendRequest request);

    /** InstallSnapshot RPC (paper §7) when the needed log prefix is compacted. */
    CompletableFuture<RaftInstallSnapshotResponse> installSnapshot(String target,
                                                                   RaftInstallSnapshotRequest request);

    /** Relay a client write to the node believed to be the leader. */
    CompletableFuture<RaftWriteForwardResponse> forwardWrite(String target,
                                                             RaftWriteForwardRequest request);

    void shutdown();
}