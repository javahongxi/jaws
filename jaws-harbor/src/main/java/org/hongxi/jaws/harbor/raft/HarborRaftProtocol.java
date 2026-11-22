package org.hongxi.jaws.harbor.raft;

import org.hongxi.jaws.harbor.model.ConfigCasConflictException;
import org.hongxi.jaws.harbor.model.request.RaftWriteForwardRequest;
import org.hongxi.jaws.harbor.model.response.RaftWriteForwardResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The write path of the CP protocol — the harbor counterpart of Nacos's
 * {@code CPProtocol}: a client write may land on ANY node, but only the leader
 * may append, so a non-leader relays the command to the leader it knows and
 * retries with the hint a rejection carries (leadership can move mid-flight).
 * <p>
 * The relay is strictly one hop: {@link #onForwardWrite} on the receiving
 * leader proposes locally and NEVER forwards again, which is what keeps a
 * stale leader view from bouncing a write between two nodes forever.
 *
 * @author shenhongxi
 */
public class HarborRaftProtocol {

    private static final Logger log = LoggerFactory.getLogger(HarborRaftProtocol.class);

    /** How long one proposal/relay may take before the caller is told to retry. */
    private static final long WRITE_TIMEOUT_MS = 5000;
    private static final int MAX_WRITE_ATTEMPTS = 3;

    private final RaftNode raftNode;
    private final RaftTransport transport;

    public HarborRaftProtocol(RaftNode raftNode, RaftTransport transport) {
        this.raftNode = raftNode;
        this.transport = transport;
    }

    public RaftNode raftNode() {
        return raftNode;
    }

    /**
     * Commit a naming command through Raft, relaying to the leader when this
     * node is not it. Returns only after the entry is committed AND applied
     * locally; throws a (retryable) {@link IllegalStateException} when no
     * leader can be reached within the attempt budget, or a (deterministic)
     * {@link ConfigCasConflictException} when the state machine refused the
     * command at apply time.
     */
    public void write(NamingCommand command) {
        writeBytes(command.toBytes());
    }

    /** Commit a config command through the same group and the same relay path. */
    public void write(ConfigCommand command) {
        writeBytes(command.toBytes());
    }

    private void writeBytes(byte[] bytes) {
        String target = null;
        for (int attempt = 0; attempt < MAX_WRITE_ATTEMPTS; attempt++) {
            if (raftNode.isLeader()) {
                try {
                    String refusal = raftNode.propose(bytes).get(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    if (refusal != null) {
                        // Committed but refused at apply (a lost CAS): deterministic,
                        // so it leaves the retry loop at once — proposing again
                        // would lose again and only mask the conflict.
                        throw new ConfigCasConflictException(refusal);
                    }
                    return;
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof RaftNode.NotLeaderException notLeader) {
                        target = notLeader.leaderId();
                        continue;
                    }
                    throw new IllegalStateException("raft write failed", e.getCause());
                } catch (TimeoutException e) {
                    throw new IllegalStateException("raft write timed out; retry later", e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("raft write interrupted", e);
                }
            }
            if (target == null) {
                target = raftNode.leaderId();
            }
            if (target == null || target.equals(raftNode.nodeId())) {
                throw new IllegalStateException("no raft leader available; retry later");
            }
            RaftWriteForwardResponse response = relay(target, bytes);
            if (response.getRefusal() != null) {
                // The leader applied and refused: same deterministic outcome as
                // a local proposal, carried back across the relay untouched.
                throw new ConfigCasConflictException(response.getRefusal());
            }
            if (response.isSuccess()) {
                return;
            }
            // The relay target was not (or no longer) the leader: follow its hint.
            target = response.getLeaderId();
        }
        throw new IllegalStateException("raft write failed after " + MAX_WRITE_ATTEMPTS
                + " attempts; retry later");
    }

    /**
     * Handle a write relayed from a peer. Leader-only and never relays further:
     * a non-leader answers with its best leader hint instead, so the origin
     * node retries against the right target rather than starting a relay loop.
     */
    public RaftWriteForwardResponse onForwardWrite(RaftWriteForwardRequest request) {
        RaftWriteForwardResponse response = new RaftWriteForwardResponse();
        response.setResultCode(200);
        if (!raftNode.isLeader()) {
            response.setSuccess(false);
            response.setMessage("not the raft leader");
            response.setLeaderId(raftNode.leaderId());
            return response;
        }
        byte[] bytes = Base64.getDecoder().decode(request.getCommand());
        try {
            String refusal = raftNode.propose(bytes).get(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (refusal != null) {
                response.setSuccess(false);
                response.setMessage(refusal);
                response.setRefusal(refusal);
            } else {
                response.setSuccess(true);
            }
        } catch (ExecutionException e) {
            response.setSuccess(false);
            response.setMessage(String.valueOf(e.getCause()));
            if (e.getCause() instanceof RaftNode.NotLeaderException notLeader) {
                response.setLeaderId(notLeader.leaderId());
            }
        } catch (TimeoutException e) {
            response.setSuccess(false);
            response.setMessage("raft write timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            response.setSuccess(false);
            response.setMessage("raft write interrupted");
        }
        return response;
    }

    private RaftWriteForwardResponse relay(String target, byte[] bytes) {
        RaftWriteForwardRequest request = new RaftWriteForwardRequest();
        request.setCommand(Base64.getEncoder().encodeToString(bytes));
        try {
            return transport.forwardWrite(target, request).get(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (ExecutionException | TimeoutException e) {
            log.warn("[raft] write relay to {} failed: {}", target, e.getMessage());
            throw new IllegalStateException("raft leader " + target + " unreachable; retry later", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("raft write relay interrupted", e);
        }
    }
}