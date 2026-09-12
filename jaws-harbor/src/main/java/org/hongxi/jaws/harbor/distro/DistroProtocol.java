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
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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
 *   <li><b>Verify</b> — periodic heartbeat that sends per-client revisions to
 *       peers for consistency checking; mismatches trigger compensating sync</li>
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

    private final ClusterManager clusterManager;
    private final HarborNodeTransport transport;
    private final ServiceStorage serviceStorage;
    private final ConnectionManager connectionManager;

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
     * Start the Distro protocol: schedule verify and load tasks.
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

        // Schedule initial load task (runs once, retries on failure)
        scheduler.schedule(this::runLoadTask, 1, TimeUnit.SECONDS);
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
                    localCache.setLastUpdatedTime(System.currentTimeMillis());
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
                if (data != null) {
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
            if (peers.isEmpty()) {
                return;
            }
            // Build per-client verify data from native clients only
            List<ClientVerifyInfo> verifyInfos = new ArrayList<>();
            for (ClientSession session : connectionManager.allNativeClientSessions()) {
                verifyInfos.add(new ClientVerifyInfo(session.getClientId(), session.getRevision()));
            }
            if (verifyInfos.isEmpty()) {
                return;
            }
            for (ClusterMember peer : peers) {
                try {
                    List<String> mismatched = transport.syncVerify(peer.address(), verifyInfos);
                    if (!mismatched.isEmpty()) {
                        log.warn("[harbor] verify mismatch with {}, pulling targeted snapshot for {} clients",
                                peer.address(), mismatched.size());
                        pullSnapshotFromPeer(peer, mismatched);
                    }
                } catch (Exception e) {
                    log.debug("[harbor] verify to {} failed: {}", peer.address(), e.getMessage());
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
                    if (pullSnapshotFromPeer(peer, null)) {
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
     * Pull a snapshot from a peer and apply only the specified clients.
     *
     * @param peer          the peer to pull from
     * @param clientFilter  if non-null, only apply entries whose clientId is in this set;
     *                      if null, apply all entries (full load)
     * @return true if snapshot was successfully loaded and applied
     */
    private boolean pullSnapshotFromPeer(ClusterMember peer, List<String> clientFilter) {
        log.info("[harbor] loading naming snapshot from {}{}", peer.address(),
                clientFilter != null ? " (filtered: " + clientFilter.size() + " clients)" : "");
        byte[] snapshot = transport.getSnapshot(peer.address());
        if (snapshot == null || snapshot.length == 0) {
            return false;
        }
        String json = new String(snapshot, StandardCharsets.UTF_8);
        List<ClientSyncData> clientDataList = JSON.parseObject(json, new TypeReference<>() {});
        if (clientDataList == null) {
            return false;
        }
        Set<String> filterSet = clientFilter != null ? Set.copyOf(clientFilter) : null;
        int applied = 0;
        for (ClientSyncData data : clientDataList) {
            String cid = data.getClientId();
            // For verify-triggered sync: only fill in MISSING clients.
            // Existing clients (mismatched or not) are skipped — their
            // consistency is maintained by the normal CHANGE sync flow.
            if (filterSet != null && connectionManager.getClientSession(cid) != null) {
                continue;
            }
            if (filterSet == null || filterSet.contains(cid)) {
                serviceStorage.applyClientSyncData(data);
                applied++;
            }
        }
        log.info("[harbor] snapshot loaded from {}: {}/{} clients applied",
                peer.address(), applied, clientDataList.size());
        return applied > 0;
    }
}
