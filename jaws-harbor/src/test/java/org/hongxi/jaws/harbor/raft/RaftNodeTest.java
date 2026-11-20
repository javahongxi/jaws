package org.hongxi.jaws.harbor.raft;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import org.hongxi.jaws.harbor.cluster.ClusterManager;
import org.hongxi.jaws.harbor.cluster.ClusterMember;
import org.hongxi.jaws.harbor.model.Instance;
import org.hongxi.jaws.harbor.model.request.RaftAppendRequest;
import org.hongxi.jaws.harbor.model.request.RaftInstallSnapshotRequest;
import org.hongxi.jaws.harbor.model.request.RaftVoteRequest;
import org.hongxi.jaws.harbor.model.request.RaftWriteForwardRequest;
import org.hongxi.jaws.harbor.model.response.RaftAppendResponse;
import org.hongxi.jaws.harbor.model.response.RaftInstallSnapshotResponse;
import org.hongxi.jaws.harbor.model.response.RaftVoteResponse;
import org.hongxi.jaws.harbor.model.response.RaftWriteForwardResponse;
import org.hongxi.jaws.rpc.URL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Unit tests for {@link RaftNode} over an in-memory transport: election,
 * replication, the leader-only commit rule, failover, partition behaviour and
 * InstallSnapshot catch-up — the algorithm itself, without any wire involved.
 *
 * @author shenhongxi
 */
class RaftNodeTest {

    @TempDir
    Path dir;

    private final InMemoryTransport transport = new InMemoryTransport();
    private final List<RaftNode> nodes = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (RaftNode node : nodes) {
            node.shutdown();
        }
    }

    /** Shrunk timings keep elections in the hundreds of milliseconds. */
    private static RaftOptions fast() {
        return new RaftOptions(150, 300, 50, 10_000);
    }

    private RaftNode node(int port, int[] peerPorts, RecordingStateMachine machine, RaftOptions options) {
        ClusterManager cluster = new ClusterManager(new URL("harbor", "127.0.0.1", port, ""));
        for (int peerPort : peerPorts) {
            cluster.addMember(new ClusterMember("127.0.0.1:" + peerPort));
        }
        RaftNode node = new RaftNode(cluster.getSelfAddress(), cluster, transport, machine,
                dir.resolve("node-" + port), options);
        transport.register(node);
        nodes.add(node);
        return node;
    }

    private static byte[] registerCommand(String clientId) {
        Instance instance = new Instance();
        instance.setIp("127.0.0.1");
        instance.setPort(9000);
        return NamingCommand.register("public", "DEFAULT_GROUP", "svc", clientId, instance).toBytes();
    }

    @Test
    void singleNodeElectsItselfAndCommitsImmediately() throws Exception {
        RecordingStateMachine machine = new RecordingStateMachine();
        RaftNode node = node(1001, new int[0], machine, fast());
        node.start();
        await(node::isLeader, "single node must elect itself");

        node.propose(registerCommand("c1")).get(2, TimeUnit.SECONDS);
        assertTrue(machine.applied.contains("REGISTER"), "the proposal must be applied: " + machine.applied);
        assertEquals(node.status().commitIndex(), node.status().lastApplied());
    }

    @Test
    void threeNodesElectOneLeaderAndReplicateToAll() throws Exception {
        RecordingStateMachine[] machines = {new RecordingStateMachine(),
                new RecordingStateMachine(), new RecordingStateMachine()};
        RaftNode a = node(1001, new int[]{1002, 1003}, machines[0], fast());
        RaftNode b = node(1002, new int[]{1001, 1003}, machines[1], fast());
        RaftNode c = node(1003, new int[]{1001, 1002}, machines[2], fast());
        List<RaftNode> group = List.of(a, b, c);
        group.forEach(RaftNode::start);

        await(() -> leaderCount(group) == 1, "exactly one leader must emerge");
        RaftNode leader = leaderOf(group);
        leader.propose(registerCommand("c1")).get(2, TimeUnit.SECONDS);
        for (RecordingStateMachine machine : machines) {
            await(() -> machine.applied.contains("REGISTER"),
                    "every node must apply the committed entry");
        }
    }

    @Test
    void followerProposalFailsWithTheLeaderHint() throws Exception {
        RaftNode a = node(1001, new int[]{1002, 1003}, new RecordingStateMachine(), fast());
        RaftNode b = node(1002, new int[]{1001, 1003}, new RecordingStateMachine(), fast());
        RaftNode c = node(1003, new int[]{1001, 1002}, new RecordingStateMachine(), fast());
        List<RaftNode> group = List.of(a, b, c);
        group.forEach(RaftNode::start);
        await(() -> leaderCount(group) == 1, "exactly one leader must emerge");
        RaftNode leader = leaderOf(group);
        RaftNode follower = group.stream().filter(n -> !n.isLeader()).findFirst().orElseThrow();

        ExecutionException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                ExecutionException.class,
                () -> follower.propose(registerCommand("c1")).get(2, TimeUnit.SECONDS));
        RaftNode.NotLeaderException notLeader =
                assertInstanceOf(RaftNode.NotLeaderException.class, thrown.getCause());
        assertEquals(leader.nodeId(), notLeader.leaderId(), "the hint must name the leader");
    }

    @Test
    void leaderDeathTriggersReelectionAndNewCommits() throws Exception {
        RecordingStateMachine[] machines = {new RecordingStateMachine(),
                new RecordingStateMachine(), new RecordingStateMachine()};
        RaftNode a = node(1001, new int[]{1002, 1003}, machines[0], fast());
        RaftNode b = node(1002, new int[]{1001, 1003}, machines[1], fast());
        RaftNode c = node(1003, new int[]{1001, 1002}, machines[2], fast());
        List<RaftNode> group = List.of(a, b, c);
        group.forEach(RaftNode::start);
        await(() -> leaderCount(group) == 1, "exactly one leader must emerge");

        RaftNode dead = leaderOf(group);
        transport.unregister(dead.nodeId());
        dead.shutdown();
        List<RaftNode> survivors = group.stream().filter(n -> n != dead).toList();
        await(() -> leaderCount(survivors) == 1, "the survivors must elect a replacement");

        RaftNode newLeader = leaderOf(survivors);
        newLeader.propose(registerCommand("after-failover")).get(2, TimeUnit.SECONDS);
        for (RaftNode survivor : survivors) {
            await(() -> survivor.status().lastApplied() >= newLeader.status().commitIndex(),
                    "the survivor must catch up to the new leader's commit");
        }
    }

    @Test
    void partitionedLeaderCannotCommitAndStepsDownWhenHealed() throws Exception {
        RaftNode a = node(1001, new int[]{1002, 1003}, new RecordingStateMachine(), fast());
        RaftNode b = node(1002, new int[]{1001, 1003}, new RecordingStateMachine(), fast());
        RaftNode c = node(1003, new int[]{1001, 1002}, new RecordingStateMachine(), fast());
        List<RaftNode> group = List.of(a, b, c);
        group.forEach(RaftNode::start);
        await(() -> leaderCount(group) == 1, "exactly one leader must emerge");
        RaftNode isolated = leaderOf(group);
        long commitBefore = isolated.status().commitIndex();

        transport.blocked.add(isolated.nodeId());
        CompletableFuture<Void> stuck = isolated.propose(registerCommand("stuck"));
        Thread.sleep(400);
        assertFalse(stuck.isDone(), "a minority leader must not commit alone");
        assertEquals(commitBefore, isolated.status().commitIndex());

        List<RaftNode> majority = group.stream().filter(n -> n != isolated).toList();
        await(() -> leaderCount(majority) == 1, "the majority must elect a new leader");

        transport.blocked.remove(isolated.nodeId());
        await(() -> !isolated.isLeader(), "the healed old leader must step down");
        assertTrue(stuck.isCompletedExceptionally(), "its uncommitted write must fail, not hang");
    }

    @Test
    void laggingFollowerCatchesUpThroughInstallSnapshot() throws Exception {
        RaftOptions compacting = new RaftOptions(150, 300, 50, 2);
        RecordingStateMachine laggingMachine = new RecordingStateMachine();
        RaftNode a = node(1001, new int[]{1002, 1003}, new RecordingStateMachine(), compacting);
        RaftNode b = node(1002, new int[]{1001, 1003}, new RecordingStateMachine(), compacting);
        RaftNode lagging = node(1003, new int[]{1001, 1002}, laggingMachine, compacting);
        List<RaftNode> group = List.of(a, b, lagging);
        // The lagging node misses everything from the start, so the leader's log
        // compacts past what it needs and only a snapshot can serve it.
        transport.blocked.add(lagging.nodeId());
        group.forEach(RaftNode::start);
        await(() -> leaderCount(List.of(a, b)) == 1, "the reachable pair must elect a leader");
        RaftNode leader = leaderOf(List.of(a, b));

        for (int i = 0; i < 4; i++) {
            leader.propose(registerCommand("c" + i)).get(2, TimeUnit.SECONDS);
        }
        await(() -> leader.status().lastApplied() >= 4, "the leader must apply all four");

        transport.blocked.remove(lagging.nodeId());
        await(() -> laggingMachine.applied.size() >= 4,
                "the lagging follower must install the snapshot and catch up");
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private static long leaderCount(List<RaftNode> group) {
        return group.stream().filter(RaftNode::isLeader).count();
    }

    private static RaftNode leaderOf(List<RaftNode> group) {
        return group.stream().filter(RaftNode::isLeader).findFirst().orElseThrow();
    }

    private static void await(BooleanSupplier condition, String message) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        fail(message);
    }

    /** Records applied operations; the snapshot image is that list as JSON. */
    private static class RecordingStateMachine implements StateMachine {
        final List<String> applied = new CopyOnWriteArrayList<>();

        @Override
        public void onApply(LogEntry entry) {
            NamingCommand command = NamingCommand.fromBytes(entry.data());
            if (!NamingCommand.OP_NOOP.equals(command.getOperation())) {
                applied.add(command.getOperation());
            }
        }

        @Override
        public byte[] saveSnapshot() {
            return JSON.toJSONBytes(applied);
        }

        @Override
        public void loadSnapshot(byte[] data) {
            List<String> image = JSON.parseObject(new String(data, StandardCharsets.UTF_8),
                    new TypeReference<>() {});
            applied.clear();
            applied.addAll(image);
        }
    }

    /** Direct node-to-node delivery with a partition switch per node id. */
    private static class InMemoryTransport implements RaftTransport {
        final Map<String, RaftNode> nodes = new ConcurrentHashMap<>();
        final Set<String> blocked = ConcurrentHashMap.newKeySet();

        void register(RaftNode node) {
            nodes.put(node.nodeId(), node);
        }

        void unregister(String nodeId) {
            nodes.remove(nodeId);
        }

        private boolean deliverable(String target, String sender) {
            return nodes.containsKey(target) && !blocked.contains(target)
                    && (sender == null || !blocked.contains(sender));
        }

        private static <T> CompletableFuture<T> lost() {
            return CompletableFuture.failedFuture(new IOException("partitioned or down"));
        }

        @Override
        public CompletableFuture<RaftVoteResponse> requestVote(String target, RaftVoteRequest request) {
            if (!deliverable(target, request.getCandidateId())) {
                return lost();
            }
            return nodes.get(target).handleVoteRequest(request);
        }

        @Override
        public CompletableFuture<RaftAppendResponse> appendEntries(String target, RaftAppendRequest request) {
            if (!deliverable(target, request.getLeaderId())) {
                return lost();
            }
            return nodes.get(target).handleAppendRequest(request);
        }

        @Override
        public CompletableFuture<RaftInstallSnapshotResponse> installSnapshot(
                String target, RaftInstallSnapshotRequest request) {
            if (!deliverable(target, request.getLeaderId())) {
                return lost();
            }
            return nodes.get(target).handleInstallSnapshot(request);
        }

        @Override
        public CompletableFuture<RaftWriteForwardResponse> forwardWrite(
                String target, RaftWriteForwardRequest request) {
            if (!deliverable(target, null)) {
                return lost();
            }
            RaftWriteForwardResponse response = new RaftWriteForwardResponse();
            response.setResultCode(200);
            response.setSuccess(nodes.get(target).isLeader());
            return CompletableFuture.completedFuture(response);
        }

        @Override
        public void shutdown() {
        }
    }
}