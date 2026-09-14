package org.hongxi.jaws.harbor.distro;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import org.hongxi.jaws.harbor.ClientSession;
import org.hongxi.jaws.harbor.ConnectionManager;
import org.hongxi.jaws.harbor.ServiceStorage;
import org.hongxi.jaws.harbor.cluster.ClusterManager;
import org.hongxi.jaws.harbor.cluster.ClusterMember;
import org.hongxi.jaws.harbor.model.ClientSyncData;
import org.hongxi.jaws.harbor.model.ClientVerifyInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Core Distro consensus protocol for the Harbor cluster.
 * <p>
 * Implements the AP (Availability + Partition tolerance) model inspired by
 * Nacos's Distro protocol. Data is replicated across all nodes with eventual
 * consistency. The protocol has three main activities:
 * <ul>
 *   <li><b>Sync</b> — when local data changes, the full client state is pushed
 *       to all peers (client-level granularity, matching Nacos)</li>
 *   <li><b>Verify</b> — periodically send per-client revisions to peers; on a
 *       mismatch the OWNER re-pushes that client's latest state to the reporting
 *       peer (targeted compensating sync), matching Nacos verify → syncToTarget</li>
 *   <li><b>Load</b> — on startup, load a snapshot of all client data from a
 *       peer to catch up on data written while this node was offline</li>
 * </ul>
 * Harbor focuses exclusively on naming (service instances).
 *
 * @author shenhongxi
 */
public class DistroProtocol {

    private static final Logger log = LoggerFactory.getLogger(DistroProtocol.class);

    public static final String OP_CHANGE = "CHANGE";
    public static final String OP_DELETE = "DELETE";

    /** Verify interval matching Nacos {@code DEFAULT_HEALTH_CHECK_INTERVAL = 5s}. */
    private static final long VERIFY_INTERVAL_MS = 5000L;

    /** Load-data retry delay on failure. */
    private static final long LOAD_DATA_RETRY_DELAY_MS = 30_000L;

    /** Coalescing window for outbound sync — mirrors PushDelayTaskEngine. */
    private static final long SYNC_MERGE_DELAY_MS = 200L;

    /**
     * How often the owner re-publishes each of its native clients, even with no
     * logical change. A replica's copy of {@code lastBeat} is frozen at push time
     * (beats arrive on the owner's connection and are not forwarded per beat,
     * matching Nacos), so without this pass a long-lived client would age toward
     * the 180s expiry window on every node that does not own its connection — and
     * those nodes would delete live data. At 1/6 of that window a replica's copy
     * is never stale enough to expire on its own, so expiry then means what it
     * should mean: the owner stopped, the client or its node.
     */
    private static final long CLIENT_REFRESH_INTERVAL_MS = 30_000L;

    private final ClusterManager clusterManager;
    private final HarborNodeTransport transport;
    private final ServiceStorage serviceStorage;
    private final ConnectionManager connectionManager;

    /** clientId → in-flight coalesced sync task; presence = merge lock. */
    private final Map<String, ScheduledFuture<?>> pendingSync = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "harbor-distro-scheduler");
                t.setDaemon(true);
                return t;
            });

    private volatile boolean running;

    public DistroProtocol(ClusterManager clusterManager,
                          HarborNodeTransport transport,
                          ServiceStorage serviceStorage,
                          ConnectionManager connectionManager) {
        this.clusterManager = clusterManager;
        this.transport = transport;
        this.serviceStorage = serviceStorage;
        this.connectionManager = connectionManager;
    }

    /**
     * Start the Distro protocol: schedule the periodic verify task and client
     * refresh, and the one-shot initial load from a peer.
     */
    public void start() {
        if (running) {
            return;
        }
        running = true;
        log.info("[harbor] distro protocol starting");

        // Schedule periodic verify task
        scheduler.scheduleAtFixedRate(this::runVerifyTask,
                VERIFY_INTERVAL_MS, VERIFY_INTERVAL_MS, TimeUnit.MILLISECONDS);

        // Schedule periodic re-publish of owned clients.
        scheduler.scheduleAtFixedRate(this::runRefreshTask,
                CLIENT_REFRESH_INTERVAL_MS, CLIENT_REFRESH_INTERVAL_MS, TimeUnit.MILLISECONDS);

        // Schedule initial load task (runs once, retries on failure)
        scheduler.schedule(this::runLoadTask, 1, TimeUnit.SECONDS);
    }

    /**
     * Re-publish every native client's CURRENT full state. Bursts collapse through
     * {@link #requestSyncChange(String)}, so a refresh racing a real change costs
     * one push, not two.
     */
    public void refreshOwnedClients() {
        for (ClientSession session : connectionManager.allNativeClientSessions()) {
            requestSyncChange(session.getClientId());
        }
    }

    private void runRefreshTask() {
        try {
            refreshOwnedClients();
        } catch (Exception e) {
            log.warn("[harbor] distro refresh task failed", e);
        }
    }

    /**
     * Shut down the Distro protocol and release resources.
     */
    public void shutdown() {
        running = false;
        scheduler.shutdownNow();
        transport.shutdown();
        log.info("[harbor] distro protocol shutdown");
    }

    public boolean isInitialized() {
        return running;
    }

    // ========================================================================
    // Outbound: triggered by local data changes
    // ========================================================================

    /**
     * Sync a client-level change to all peer nodes.
     *
     * @param connectionId the clientId (connectionId) whose data changed
     * @param operation    {@link #OP_CHANGE} or {@link #OP_DELETE}
     * @param content      serialized {@link ClientSyncData} for CHANGE; empty for DELETE
     */
    public void syncChange(String connectionId, String operation, byte[] content) {
        Set<ClusterMember> peers = clusterManager.allMembersExceptSelf();
        if (peers.isEmpty()) {
            return;
        }
        for (ClusterMember peer : peers) {
            try {
                boolean ok = transport.syncData(peer.address(), connectionId, operation, content);
                if (!ok) {
                    log.warn("[harbor] distro sync failed: {} -> {}",
                            connectionId, peer.address());
                }
            } catch (Exception e) {
                log.warn("[harbor] distro sync error: {} -> {}",
                        connectionId, peer.address(), e);
            }
        }
    }

    /**
     * Register that a client's published data changed. Coalescing + reconcile:
     * bursts for the same clientId collapse into one push, and at fire time we
     * re-read the client's CURRENT full state (never a captured snapshot) and
     * push it to all peers — the same shape as the harbor {@code PushDelayTaskEngine}
     * and Nacos's {@code DistroDelayTaskExecuteEngine}. An emptied client
     * propagates a DELETE. This removes the old "push full state on every change"
     * amplification.
     */
    public void requestSyncChange(String clientId) {
        if (clientId == null) {
            return;
        }
        pendingSync.computeIfAbsent(clientId, k ->
                scheduler.schedule(() -> doSyncChange(k), SYNC_MERGE_DELAY_MS, TimeUnit.MILLISECONDS));
    }

    private void doSyncChange(String clientId) {
        pendingSync.remove(clientId);
        if (!running) {
            return;
        }
        ClientSyncData data = serviceStorage.buildClientSyncData(clientId);
        if (data == null) {
            return;
        }
        if (hasContent(data)) {
            syncChange(clientId, OP_CHANGE, JSON.toJSONBytes(data));
        } else {
            syncChange(clientId, OP_DELETE, new byte[0]);
        }
    }

    /**
     * Immediately propagate a client's removal and cancel any coalesced CHANGE
     * pending for it — a DELETE must not be swallowed by a queued window.
     */
    public void requestSyncDelete(String clientId) {
        if (clientId == null) {
            return;
        }
        ScheduledFuture<?> f = pendingSync.remove(clientId);
        if (f != null) {
            f.cancel(false);
        }
        syncChange(clientId, OP_DELETE, new byte[0]);
    }

    // ========================================================================
    // Inbound: triggered by peer requests
    // ========================================================================

    /**
     * Handle a sync request from a peer node — apply the received client data locally.
     *
     * @param clientId  the connectionId of the client (resourceKey)
     * @param operation CHANGE or DELETE
     * @param content   serialized ClientSyncData (CHANGE) or empty (DELETE)
     */
    public boolean onSync(String clientId, String operation, byte[] content) {
        log.debug("[harbor] distro receive: clientId={} op={}", clientId, operation);
        try {
            if (OP_DELETE.equals(operation)) {
                log.info("[harbor] distro delete client: {}", clientId);
                serviceStorage.removeSyncedClient(clientId);
            } else {
                if (content != null && content.length != 0) {
                    ClientSyncData data = JSON.parseObject(
                            new String(content, StandardCharsets.UTF_8), ClientSyncData.class);
                    if (data != null) {
                        serviceStorage.applyClientSyncData(data);
                    }
                }
            }
            return true;
        } catch (Exception e) {
            log.error("[harbor] error processing distro receive: clientId={}", clientId, e);
            return false;
        }
    }

    /**
     * Handle a verify request from a peer — compare per-client revisions.
     *
     * @param verifyInfos list of (clientId, revision) from the peer
     * @return list of clientIds that are missing or have mismatched revisions;
     *         empty if all matched
     */
    public List<String> onVerify(List<ClientVerifyInfo> verifyInfos) {
        log.debug("[harbor] distro verify: {} clients", verifyInfos.size());
        List<String> mismatched = new ArrayList<>();
        for (ClientVerifyInfo info : verifyInfos) {
            ClientSession localCache = connectionManager.getClientSession(info.getClientId());
            if (localCache != null) {
                if (localCache.getRevision() == info.getRevision()) {
                    localCache.markOwnerConfirmed();
                } else {
                    log.info("[harbor] distro verify mismatch: clientId={} localRev={} remoteRev={}",
                            info.getClientId(), localCache.getRevision(), info.getRevision());
                    mismatched.add(info.getClientId());
                }
            } else {
                log.debug("[harbor] distro verify: unknown clientId={}", info.getClientId());
                mismatched.add(info.getClientId());
            }
        }
        return mismatched;
    }

    /**
     * Handle a snapshot request from a peer — return all client data as snapshot.
     */
    public byte[] onSnapshot() {
        log.info("[harbor] distro snapshot requested");
        try {
            List<ClientSyncData> allClientData = new ArrayList<>();
            for (ClientSession session : connectionManager.allClientSessions()) {
                ClientSyncData data = serviceStorage.buildClientSyncData(session.getClientId());
                if (data != null && hasContent(data)) {
                    allClientData.add(data);
                }
            }
            return JSON.toJSONBytes(allClientData);
        } catch (Exception e) {
            log.error("[harbor] error building snapshot", e);
        }
        return new byte[0];
    }

    // ========================================================================
    // Scheduled tasks
    // ========================================================================

    /**
     * Periodic verify: send per-client revisions to all peers.
     */
    private void runVerifyTask() {
        try {
            Set<ClusterMember> peers = clusterManager.allMembersExceptSelf();
            if (!peers.isEmpty()) {
                // Build per-client verify data from native clients only
                List<ClientVerifyInfo> verifyInfos = new ArrayList<>();
                for (ClientSession session : connectionManager.allNativeClientSessions()) {
                    verifyInfos.add(new ClientVerifyInfo(session.getClientId(), session.getRevision()));
                }
                if (!verifyInfos.isEmpty()) {
                    for (ClusterMember peer : peers) {
                        try {
                            List<String> mismatched = transport.syncVerify(peer.address(), verifyInfos);
                            if (!mismatched.isEmpty()) {
                                log.warn("[harbor] verify mismatch with {}, re-pushing latest state of {} client(s) to it",
                                        peer.address(), mismatched.size());
                                resyncToPeer(peer, mismatched);
                            }
                        } catch (Exception e) {
                            log.debug("[harbor] verify to {} failed: {}", peer.address(), e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[harbor] verify task error", e);
        }
    }

    /**
     * Initial load: fetch snapshot from a peer if we have no data.
     */
    private void runLoadTask() {
        try {
            Set<ClusterMember> peers = clusterManager.allMembersExceptSelf();
            if (peers.isEmpty()) {
                log.info("[harbor] no peers to load from — running as single node");
                return;
            }
            for (ClusterMember peer : peers) {
                try {
                    if (pullSnapshotFromPeer(peer)) {
                        break;
                    }
                } catch (Exception e) {
                    log.warn("[harbor] load from {} failed: {}", peer.address(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("[harbor] load task error", e);
            // Retry after delay
            if (running) {
                scheduler.schedule(this::runLoadTask,
                        LOAD_DATA_RETRY_DELAY_MS, TimeUnit.MILLISECONDS);
            }
        }
    }

    /**
     * Startup load: pull a full snapshot from a peer and apply clients we don't
     * already hold. Any client we already have (native, or freshly synced from
     * its owner) is left intact — we never overwrite a native session here.
     */
    private boolean pullSnapshotFromPeer(ClusterMember peer) {
        log.info("[harbor] loading naming snapshot from {}", peer.address());
        byte[] snapshot = transport.getSnapshot(peer.address());
        if (snapshot == null || snapshot.length == 0) {
            return false;
        }
        String json = new String(snapshot, StandardCharsets.UTF_8);
        List<ClientSyncData> clientDataList = JSON.parseObject(json, new TypeReference<>() {});
        if (clientDataList == null) {
            return false;
        }
        int applied = 0;
        for (ClientSyncData data : clientDataList) {
            String cid = data.getClientId();
            if (connectionManager.getClientSession(cid) != null) {
                continue;   // already hold this client (native or newer) — don't clobber
            }
            serviceStorage.applyClientSyncData(data);
            applied++;
        }
        log.info("[harbor] snapshot loaded from {}: {}/{} clients applied",
                peer.address(), applied, clientDataList.size());
        return applied > 0;
    }

    /**
     * Targeted compensating sync — the correct repair direction. When a peer
     * reports that its copy of one of OUR native clients is stale or missing,
     * the owner re-pushes that client's CURRENT full state to exactly that peer.
     * This mirrors Nacos: a verify failure triggers the responsible node to
     * {@code syncToTarget(ADD)} the client back to the verifying peer. The owner
     * holds the authoritative copy, so pulling from the (stale) peer would be
     * wrong — and the previous pull path was additionally self-defeating, since
     * the mismatched client is by definition a native one we already hold locally.
     */
    // Package-private (not private) so a same-package unit test can drive the
    // verify-repair directly instead of waiting on the 5s verify scheduler.
    void resyncToPeer(ClusterMember peer, List<String> clientIds) {
        for (String cid : clientIds) {
            ClientSession session = connectionManager.getClientSession(cid);
            if (session == null || !session.isNativeClient()) {
                continue;   // not ours to repair — skip
            }
            ClientSyncData data = serviceStorage.buildClientSyncData(cid);
            if (data == null || !hasContent(data)) {
                continue;
            }
            try {
                boolean ok = transport.syncData(peer.address(), cid, OP_CHANGE,
                        JSON.toJSONBytes(data));
                if (!ok) {
                    log.warn("[harbor] verify-triggered resync failed: {} -> {}", cid, peer.address());
                }
            } catch (Exception e) {
                log.warn("[harbor] verify-triggered resync error: {} -> {}", cid, peer.address(), e);
            }
        }
    }

    /**
     * Check whether a ClientSyncData carries anything worth replicating. A payload
     * with no instances is a zombie session — its instances all expired, or it only
     * subscribed (and subscriptions stay local) — so it must not be synced or
     * snapshot-loaded.
     */
    private static boolean hasContent(ClientSyncData data) {
        return data.getInstances() != null && !data.getInstances().isEmpty();
    }
}
