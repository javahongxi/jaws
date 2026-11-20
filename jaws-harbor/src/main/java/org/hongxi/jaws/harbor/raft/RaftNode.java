package org.hongxi.jaws.harbor.raft;

import org.hongxi.jaws.harbor.cluster.ClusterManager;
import org.hongxi.jaws.harbor.cluster.ClusterMember;
import org.hongxi.jaws.harbor.model.RaftEntry;
import org.hongxi.jaws.harbor.model.request.RaftAppendRequest;
import org.hongxi.jaws.harbor.model.request.RaftInstallSnapshotRequest;
import org.hongxi.jaws.harbor.model.request.RaftVoteRequest;
import org.hongxi.jaws.harbor.model.response.RaftAppendResponse;
import org.hongxi.jaws.harbor.model.response.RaftInstallSnapshotResponse;
import org.hongxi.jaws.harbor.model.response.RaftVoteResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * A minimal but complete Raft node (paper: "In Search of an Understandable
 * Consensus Algorithm") — the CP engine behind harbor's persistent instances,
 * playing the role SOFA JRaft plays inside Nacos.
 * <p>
 * Threading model: ALL mutable state lives on one dedicated raft thread. RPC
 * arrivals, transport replies, timer ticks and client proposals are posted as
 * events to that thread's queue, so the algorithm needs no locks — the same
 * single-writer discipline JRaft achieves with its disruptor. The timer thread
 * only posts; the transport never touches node state directly.
 * <p>
 * Covered: randomized leader election with the up-to-date-log voting
 * restriction (§5.4.1), log replication with conflict truncation and fast
 * next-index back-up (§5.3), commit/apply with the current-term-only commit
 * rule plus the new-leader NOOP (§5.4.2, Figure 8), snapshot compaction and
 * InstallSnapshot (§7), and durable term/vote/log before any acknowledgement
 * (§5.2 "persist before responding"). Recovery replays the log into the
 * {@link RaftLog} structure but only applies the snapshot to the state machine:
 * uncommitted tail entries must wait for a leader to vouch for them again.
 * <p>
 * Membership is static for the node's lifetime: peers are captured from the
 * {@link ClusterManager} at {@link #start()} (joint consensus / §6 is out of
 * scope, matching the "minimal Raft" goal). A single-node group elects itself
 * immediately, which is what makes a standalone harbor work unchanged.
 *
 * @author shenhongxi
 */
public class RaftNode {

    private static final Logger log = LoggerFactory.getLogger(RaftNode.class);

    /** Upper bound of entries per AppendEntries batch, to bound one RPC's size. */
    private static final int MAX_ENTRIES_PER_APPEND = 128;

    private enum Role { FOLLOWER, CANDIDATE, LEADER }

    /** Immutable external view of the node, refreshed after every event. */
    public record RaftStatus(String role, long term, String leaderId,
                             long commitIndex, long lastApplied, long lastIndex) {
    }

    /** Raised when a proposal reaches a non-leader; carries the best leader hint. */
    public static class NotLeaderException extends RuntimeException {
        private final String leaderId;

        public NotLeaderException(String leaderId) {
            super("not the raft leader" + (leaderId == null ? "" : "; leader is " + leaderId));
            this.leaderId = leaderId;
        }

        public String leaderId() {
            return leaderId;
        }
    }

    private final String nodeId;
    private final ClusterManager clusterManager;
    private final RaftTransport transport;
    private final StateMachine stateMachine;
    private final RaftStorage storage;
    private final RaftOptions options;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "harbor-raft");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "harbor-raft-timer");
        t.setDaemon(true);
        return t;
    });

    // ---- Raft state, all confined to the raft thread ----
    private final RaftLog raftLog = new RaftLog();
    private List<String> peers = List.of();
    private Role role = Role.FOLLOWER;
    private long currentTerm;
    private String votedFor;
    private String leaderId;
    private long commitIndex;
    private long lastApplied;
    private long electionDeadline;
    private int votesGranted;
    private int appliedSinceSnapshot;
    private final Map<String, Long> nextIndex = new HashMap<>();
    private final Map<String, Long> matchIndex = new HashMap<>();
    /** index → client write awaiting commit; completed on apply, failed on step-down. */
    private final Map<Long, CompletableFuture<Void>> pendingWrites = new HashMap<>();

    private volatile boolean running;
    private volatile RaftStatus status = new RaftStatus("FOLLOWER", 0, null, 0, 0, 0);

    public RaftNode(String nodeId, ClusterManager clusterManager, RaftTransport transport,
                    StateMachine stateMachine, Path dataDir, RaftOptions options) {
        this.nodeId = nodeId;
        this.clusterManager = clusterManager;
        this.transport = transport;
        this.stateMachine = stateMachine;
        this.storage = new RaftStorage(dataDir);
        this.options = options;
    }

    // ========================================================================
    // Lifecycle
    // ========================================================================

    /**
     * Recover durable state, capture the peer set and start the timers. A
     * single-member group starts an election at once so a standalone node
     * becomes leader without waiting out an election timeout.
     */
    public void start() {
        if (running) {
            return;
        }
        RaftStorage.Meta meta = storage.loadMeta();
        if (meta != null) {
            currentTerm = meta.currentTerm();
            votedFor = meta.votedFor();
        }
        RaftStorage.Snapshot snapshot = storage.loadSnapshot();
        if (snapshot != null) {
            raftLog.reset(snapshot.lastIncludedIndex(), snapshot.lastIncludedTerm());
            stateMachine.loadSnapshot(snapshot.data());
            commitIndex = snapshot.lastIncludedIndex();
            lastApplied = snapshot.lastIncludedIndex();
        }
        raftLog.appendRecovered(storage.loadLog(raftLog.lastIncludedIndex()));
        peers = clusterManager.allMembersExceptSelf().stream().map(ClusterMember::address).toList();
        running = true;
        resetElectionDeadline();
        long checkInterval = Math.max(1, options.electionTimeoutMinMs() / 2);
        timer.scheduleAtFixedRate(() -> post(this::checkElectionTimeout),
                checkInterval, checkInterval, TimeUnit.MILLISECONDS);
        timer.scheduleAtFixedRate(() -> post(this::heartbeat),
                options.heartbeatMs(), options.heartbeatMs(), TimeUnit.MILLISECONDS);
        if (peers.isEmpty()) {
            post(this::startElection);
        }
        publishStatus();
        log.info("[raft] node {} started: term={}, peers={}, log=[{}..{}], committed={}",
                nodeId, currentTerm, peers, raftLog.lastIncludedIndex(), raftLog.lastIndex(), commitIndex);
    }

    public void shutdown() {
        running = false;
        timer.shutdownNow();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        storage.close();
        log.info("[raft] node {} shutdown", nodeId);
    }

    // ========================================================================
    // External reads (any thread)
    // ========================================================================

    public RaftStatus status() {
        return status;
    }

    public boolean isLeader() {
        RaftStatus snapshot = status;
        return "LEADER".equals(snapshot.role()) && nodeId.equals(snapshot.leaderId());
    }

    public String leaderId() {
        return status.leaderId();
    }

    public String nodeId() {
        return nodeId;
    }

    /** The durability directory this node's log/snapshot/meta files live in. */
    public Path dataDir() {
        return storage.dir();
    }

    // ========================================================================
    // Client proposals
    // ========================================================================

    /**
     * Propose a state-machine command. The returned future completes when the
     * entry is committed AND applied locally; it fails with
     * {@link NotLeaderException} when this node is not (or no longer) the
     * leader, so the caller can relay to the right node.
     */
    public CompletableFuture<Void> propose(byte[] command) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        post(() -> {
            if (role != Role.LEADER) {
                future.completeExceptionally(new NotLeaderException(leaderId));
                return;
            }
            LogEntry entry = new LogEntry(raftLog.lastIndex() + 1, currentTerm, command);
            raftLog.appendLocal(entry);
            storage.appendLog(List.of(entry));
            pendingWrites.put(entry.index(), future);
            replicateAll();
            maybeAdvanceCommit();
        });
        return future;
    }

    // ========================================================================
    // RPC handlers (called from transport threads, executed on the raft thread)
    // ========================================================================

    public CompletableFuture<RaftVoteResponse> handleVoteRequest(RaftVoteRequest request) {
        CompletableFuture<RaftVoteResponse> future = new CompletableFuture<>();
        post(() -> future.complete(onVoteRequest(request)));
        return future;
    }

    public CompletableFuture<RaftAppendResponse> handleAppendRequest(RaftAppendRequest request) {
        CompletableFuture<RaftAppendResponse> future = new CompletableFuture<>();
        post(() -> future.complete(onAppendEntries(request)));
        return future;
    }

    public CompletableFuture<RaftInstallSnapshotResponse> handleInstallSnapshot(
            RaftInstallSnapshotRequest request) {
        CompletableFuture<RaftInstallSnapshotResponse> future = new CompletableFuture<>();
        post(() -> future.complete(onInstallSnapshot(request)));
        return future;
    }

    private RaftVoteResponse onVoteRequest(RaftVoteRequest request) {
        if (request.getTerm() < currentTerm) {
            return voteReply(false);
        }
        if (request.getTerm() > currentTerm) {
            stepDown(request.getTerm());
        }
        boolean granted = (votedFor == null || votedFor.equals(request.getCandidateId()))
                && logIsUpToDate(request.getLastLogIndex(), request.getLastLogTerm());
        if (granted) {
            votedFor = request.getCandidateId();
            storage.saveMeta(currentTerm, votedFor);
            resetElectionDeadline();
            log.info("[raft] {} voted for {} in term {}", nodeId, request.getCandidateId(), currentTerm);
        }
        return voteReply(granted);
    }

    /** Election restriction (§5.4.1): the candidate's log must be at least as new. */
    private boolean logIsUpToDate(long lastLogIndex, long lastLogTerm) {
        if (lastLogTerm != raftLog.lastTerm()) {
            return lastLogTerm > raftLog.lastTerm();
        }
        return lastLogIndex >= raftLog.lastIndex();
    }

    private RaftVoteResponse voteReply(boolean granted) {
        RaftVoteResponse response = new RaftVoteResponse();
        response.setResultCode(200);
        response.setSuccess(true);
        response.setTerm(currentTerm);
        response.setVoteGranted(granted);
        return response;
    }

    private RaftAppendResponse onAppendEntries(RaftAppendRequest request) {
        if (request.getTerm() < currentTerm) {
            return appendReply(false);
        }
        if (request.getTerm() > currentTerm) {
            stepDown(request.getTerm());
        } else if (role == Role.CANDIDATE) {
            // A valid leader exists in our own term: concede the election.
            role = Role.FOLLOWER;
        }
        leaderId = request.getLeaderId();
        resetElectionDeadline();

        List<LogEntry> incoming = new ArrayList<>();
        if (request.getEntries() != null) {
            for (RaftEntry dto : request.getEntries()) {
                incoming.add(LogEntry.fromDto(dto));
            }
        }
        if (request.getPrevLogIndex() < raftLog.lastIncludedIndex()) {
            // The prev point is inside our snapshot: it matches by definition,
            // and entries the snapshot already covers are skipped.
            incoming.removeIf(entry -> entry.index() <= raftLog.lastIncludedIndex());
        } else if (raftLog.termAt(request.getPrevLogIndex()) != request.getPrevLogTerm()) {
            return appendReply(false);
        }
        boolean conflicts = raftLog.conflicts(incoming);
        List<LogEntry> appended = raftLog.appendFollower(incoming);
        if (conflicts) {
            storage.rewriteLog(raftLog.allEntries());
        } else {
            storage.appendLog(appended);
        }
        if (request.getLeaderCommit() > commitIndex) {
            commitIndex = Math.min(request.getLeaderCommit(), raftLog.lastIndex());
            applyCommitted();
        }
        return appendReply(true);
    }

    private RaftAppendResponse appendReply(boolean success) {
        RaftAppendResponse response = new RaftAppendResponse();
        response.setResultCode(200);
        response.setSuccess(success);
        response.setTerm(currentTerm);
        response.setLastLogIndex(raftLog.lastIndex());
        return response;
    }

    private RaftInstallSnapshotResponse onInstallSnapshot(RaftInstallSnapshotRequest request) {
        RaftInstallSnapshotResponse response = new RaftInstallSnapshotResponse();
        response.setResultCode(200);
        if (request.getTerm() < currentTerm) {
            response.setSuccess(false);
            response.setTerm(currentTerm);
            return response;
        }
        if (request.getTerm() > currentTerm) {
            stepDown(request.getTerm());
        }
        role = Role.FOLLOWER;
        leaderId = request.getLeaderId();
        resetElectionDeadline();
        if (request.getLastIncludedIndex() > raftLog.lastIncludedIndex()) {
            byte[] data = Base64.getDecoder().decode(request.getData());
            storage.saveSnapshot(request.getLastIncludedIndex(), request.getLastIncludedTerm(), data);
            stateMachine.loadSnapshot(data);
            raftLog.reset(request.getLastIncludedIndex(), request.getLastIncludedTerm());
            storage.rewriteLog(List.of());
            commitIndex = Math.max(commitIndex, request.getLastIncludedIndex());
            lastApplied = request.getLastIncludedIndex();
            appliedSinceSnapshot = 0;
            log.info("[raft] {} installed snapshot from {} through index {} (term {})",
                    nodeId, request.getLeaderId(), request.getLastIncludedIndex(),
                    request.getLastIncludedTerm());
        }
        response.setSuccess(true);
        response.setTerm(currentTerm);
        return response;
    }

    // ========================================================================
    // Election (raft thread)
    // ========================================================================

    private void checkElectionTimeout() {
        if (role != Role.LEADER && System.currentTimeMillis() >= electionDeadline) {
            startElection();
        }
    }

    private void startElection() {
        role = Role.CANDIDATE;
        currentTerm++;
        votedFor = nodeId;
        storage.saveMeta(currentTerm, votedFor);
        leaderId = null;
        votesGranted = 1;
        resetElectionDeadline();
        log.info("[raft] {} starting election for term {}", nodeId, currentTerm);
        if (peers.isEmpty()) {
            becomeLeader();
            return;
        }
        RaftVoteRequest request = new RaftVoteRequest();
        request.setTerm(currentTerm);
        request.setCandidateId(nodeId);
        request.setLastLogIndex(raftLog.lastIndex());
        request.setLastLogTerm(raftLog.lastTerm());
        long electionTerm = currentTerm;
        for (String peer : peers) {
            transport.requestVote(peer, request).whenComplete((response, error) -> post(() -> {
                if (error != null || response == null) {
                    return;
                }
                if (response.getTerm() > currentTerm) {
                    stepDown(response.getTerm());
                    return;
                }
                if (role != Role.CANDIDATE || currentTerm != electionTerm) {
                    return;
                }
                if (response.isVoteGranted()) {
                    votesGranted++;
                    if (votesGranted > (peers.size() + 1) / 2) {
                        becomeLeader();
                    }
                }
            }));
        }
    }

    private void becomeLeader() {
        role = Role.LEADER;
        leaderId = nodeId;
        nextIndex.clear();
        matchIndex.clear();
        for (String peer : peers) {
            nextIndex.put(peer, raftLog.lastIndex() + 1);
            matchIndex.put(peer, 0L);
        }
        // Figure 8: a blank entry in the new term lets previous-term entries
        // commit safely once this one does.
        LogEntry noop = new LogEntry(raftLog.lastIndex() + 1, currentTerm, NamingCommand.noopBytes());
        raftLog.appendLocal(noop);
        storage.appendLog(List.of(noop));
        log.info("[raft] {} became leader for term {} (noop at index {})",
                nodeId, currentTerm, noop.index());
        replicateAll();
        maybeAdvanceCommit();
    }

    /** Leave leadership/candidacy for a newer term; pending writes cannot commit here. */
    private void stepDown(long newTerm) {
        currentTerm = newTerm;
        votedFor = null;
        storage.saveMeta(currentTerm, null);
        role = Role.FOLLOWER;
        leaderId = null;
        votesGranted = 0;
        nextIndex.clear();
        matchIndex.clear();
        resetElectionDeadline();
        failPendingWrites();
    }

    private void failPendingWrites() {
        for (CompletableFuture<Void> future : pendingWrites.values()) {
            future.completeExceptionally(new NotLeaderException(leaderId));
        }
        pendingWrites.clear();
    }

    private void resetElectionDeadline() {
        long timeout = ThreadLocalRandom.current().nextLong(
                options.electionTimeoutMinMs(), options.electionTimeoutMaxMs() + 1);
        electionDeadline = System.currentTimeMillis() + timeout;
    }

    // ========================================================================
    // Replication (raft thread, leader only)
    // ========================================================================

    private void heartbeat() {
        if (role == Role.LEADER) {
            replicateAll();
        }
    }

    private void replicateAll() {
        for (String peer : peers) {
            sendAppend(peer);
        }
    }

    private void sendAppend(String peer) {
        long next = nextIndex.getOrDefault(peer, raftLog.lastIndex() + 1);
        if (next <= raftLog.lastIncludedIndex()) {
            sendInstallSnapshot(peer);
            return;
        }
        long prevIndex = next - 1;
        RaftAppendRequest request = new RaftAppendRequest();
        request.setTerm(currentTerm);
        request.setLeaderId(nodeId);
        request.setPrevLogIndex(prevIndex);
        request.setPrevLogTerm(prevIndex == 0 ? 0 : raftLog.termAt(prevIndex));
        request.setLeaderCommit(commitIndex);
        List<LogEntry> batch = raftLog.from(next, MAX_ENTRIES_PER_APPEND);
        request.setEntries(batch.stream().map(LogEntry::toDto).toList());
        long electionTerm = currentTerm;
        int sent = batch.size();
        transport.appendEntries(peer, request).whenComplete((response, error) -> post(() -> {
            if (error != null || response == null) {
                return;
            }
            if (response.getTerm() > currentTerm) {
                stepDown(response.getTerm());
                return;
            }
            if (role != Role.LEADER || currentTerm != electionTerm) {
                return;
            }
            if (response.isSuccess()) {
                long matched = prevIndex + sent;
                matchIndex.put(peer, matched);
                nextIndex.put(peer, matched + 1);
                maybeAdvanceCommit();
                if (raftLog.lastIndex() > matched) {
                    sendAppend(peer);
                }
            } else {
                // Fast back-up (§5.3 optimization): jump to the follower's tail
                // instead of decrementing one entry per round trip. Clamped at 1
                // so a follower behind our snapshot falls into InstallSnapshot.
                long backed = Math.max(1, Math.min(next - 1, response.getLastLogIndex() + 1));
                nextIndex.put(peer, backed);
                sendAppend(peer);
            }
        }));
    }

    private void sendInstallSnapshot(String peer) {
        byte[] image = takeSnapshot();
        RaftInstallSnapshotRequest request = new RaftInstallSnapshotRequest();
        request.setTerm(currentTerm);
        request.setLeaderId(nodeId);
        request.setLastIncludedIndex(raftLog.lastIncludedIndex());
        request.setLastIncludedTerm(raftLog.lastIncludedTerm());
        request.setData(Base64.getEncoder().encodeToString(image));
        long electionTerm = currentTerm;
        long boundary = request.getLastIncludedIndex();
        transport.installSnapshot(peer, request).whenComplete((response, error) -> post(() -> {
            if (error != null || response == null) {
                return;
            }
            if (response.getTerm() > currentTerm) {
                stepDown(response.getTerm());
                return;
            }
            if (role != Role.LEADER || currentTerm != electionTerm) {
                return;
            }
            if (response.isSuccess()) {
                matchIndex.put(peer, boundary);
                nextIndex.put(peer, boundary + 1);
                maybeAdvanceCommit();
                sendAppend(peer);
            }
        }));
    }

    /**
     * §5.4.2: only entries of the CURRENT term may advance the commit index by
     * counting replicas; older entries ride along once a current-term entry
     * above them commits (the NOOP guarantees one exists).
     */
    private void maybeAdvanceCommit() {
        if (role != Role.LEADER) {
            return;
        }
        for (long index = raftLog.lastIndex(); index > commitIndex; index--) {
            if (raftLog.termAt(index) != currentTerm) {
                continue;
            }
            int replicas = 1; // self always matches its own log
            for (String peer : peers) {
                if (matchIndex.getOrDefault(peer, 0L) >= index) {
                    replicas++;
                }
            }
            if (replicas > (peers.size() + 1) / 2) {
                commitIndex = index;
                break;
            }
        }
        applyCommitted();
    }

    private void applyCommitted() {
        while (lastApplied < commitIndex) {
            lastApplied++;
            LogEntry entry = raftLog.entryAt(lastApplied);
            if (entry != null) {
                stateMachine.onApply(entry);
            }
            appliedSinceSnapshot++;
            CompletableFuture<Void> future = pendingWrites.remove(lastApplied);
            if (future != null) {
                future.complete(null);
            }
        }
        if (appliedSinceSnapshot >= options.snapshotThreshold()) {
            takeSnapshot();
        }
    }

    /**
     * Compact the applied prefix into a snapshot at a stable applied point.
     * Also serves InstallSnapshot: the returned image always matches the log
     * boundary the caller reads right after.
     */
    private byte[] takeSnapshot() {
        if (lastApplied <= raftLog.lastIncludedIndex()) {
            return stateMachine.saveSnapshot();
        }
        long index = lastApplied;
        long term = raftLog.termAt(index);
        byte[] image = stateMachine.saveSnapshot();
        storage.saveSnapshot(index, term, image);
        raftLog.truncatePrefix(index, term);
        storage.rewriteLog(raftLog.allEntries());
        appliedSinceSnapshot = 0;
        log.info("[raft] {} compacted log through index {} (term {})", nodeId, index, term);
        return image;
    }

    // ========================================================================
    // Event plumbing
    // ========================================================================

    private void post(Runnable task) {
        if (!running) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (Exception e) {
                    log.error("[raft] event failed on node {}", nodeId, e);
                }
                publishStatus();
            });
        } catch (RejectedExecutionException shuttingDown) {
            // Node is closing; dropping the event is the intended behavior.
        }
    }

    private void publishStatus() {
        status = new RaftStatus(role.name(), currentTerm, leaderId,
                commitIndex, lastApplied, raftLog.lastIndex());
    }
}