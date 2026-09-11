package org.hongxi.jaws.harbor.distro;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import org.hongxi.jaws.harbor.ServiceStorage;
import org.hongxi.jaws.harbor.cluster.ClusterManager;
import org.hongxi.jaws.harbor.cluster.ClusterMember;
import org.hongxi.jaws.harbor.model.Instance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
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
 *   <li><b>Sync</b> — when local data changes, the change is pushed to all peers</li>
 *   <li><b>Verify</b> — periodic heartbeat that sends checksums to peers for
 *       consistency checking</li>
 *   <li><b>Load</b> — on startup, load a snapshot from a peer to catch up on
 *       data that was written while this node was offline</li>
 * </ul>
 * Two resource types are managed: {@code naming} (service instances).
 *
 * @author shenhongxi
 */
public class DistroProtocol {

    private static final Logger log = LoggerFactory.getLogger(DistroProtocol.class);

    public static final String RESOURCE_NAMING = "naming";

    public static final String OP_CHANGE = "CHANGE";
    public static final String OP_DELETE = "DELETE";

    private final ClusterManager clusterManager;
    private final DistroConfig distroConfig;
    private final HarborNodeTransport transport;
    private final ServiceStorage serviceStorage;

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "harbor-distro-scheduler");
                t.setDaemon(true);
                return t;
            });

    private volatile boolean initialized = false;
    private volatile boolean running = false;

    public DistroProtocol(ClusterManager clusterManager,
                          DistroConfig distroConfig,
                          HarborNodeTransport transport,
                          ServiceStorage serviceStorage) {
        this.clusterManager = clusterManager;
        this.distroConfig = distroConfig;
        this.transport = transport;
        this.serviceStorage = serviceStorage;
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
                distroConfig.getVerifyIntervalMillis(),
                distroConfig.getVerifyIntervalMillis(),
                TimeUnit.MILLISECONDS);

        // Schedule initial load task (runs once, retries on failure)
        scheduler.schedule(this::runLoadTask, 1, TimeUnit.SECONDS);

        initialized = true;
    }

    /**
     * Shut down the Distro protocol and release resources.
     */
    public void shutdown() {
        running = false;
        scheduler.shutdownNow();
        transport.shutdown();
        log.info("[harbor] distro protocol shut down");
    }

    public boolean isInitialized() {
        return initialized;
    }

    // ========================================================================
    // Outbound: triggered by local data changes
    // ========================================================================

    /**
     * Sync a naming data change to all peer nodes.
     */
    public void syncNamingChange(String resourceKey, String operation, byte[] content) {
        syncChange(RESOURCE_NAMING, resourceKey, operation, content);
    }

    private void syncChange(String resourceType, String resourceKey,
                            String operation, byte[] content) {
        Set<ClusterMember> peers = clusterManager.allMembersExceptSelf();
        if (peers.isEmpty()) {
            return;
        }
        for (ClusterMember peer : peers) {
            try {
                boolean ok = transport.syncData(peer.address(), resourceType,
                        resourceKey, operation, content);
                if (!ok) {
                    log.warn("[harbor] distro sync failed: {} {} -> {}",
                            resourceType, resourceKey, peer.address());
                }
            } catch (Exception e) {
                log.warn("[harbor] distro sync error: {} {} -> {}",
                        resourceType, resourceKey, peer.address(), e);
            }
        }
    }

    // ========================================================================
    // Inbound: triggered by peer requests
    // ========================================================================

    /**
     * Handle a sync request from a peer node — apply the received data locally.
     */
    public boolean onReceive(String resourceType, String resourceKey,
                             String operation, byte[] content) {
        log.debug("[harbor] distro receive: {} {} op={}", resourceType, resourceKey, operation);
        try {
            if (RESOURCE_NAMING.equals(resourceType)) {
                handleNamingSync(resourceKey, operation, content);
                return true;
            }
            log.warn("[harbor] unknown distro resource type: {}", resourceType);
            return false;
        } catch (Exception e) {
            log.error("[harbor] error processing distro receive: {} {}", resourceType, resourceKey, e);
            return false;
        }
    }

    /**
     * Handle a verify request from a peer — compare checksums.
     */
    public boolean onVerify(String resourceType, Map<String, String> checksums) {
        log.debug("[harbor] distro verify: {} checksums={}", resourceType, checksums);
        // For now, just acknowledge — a full implementation would compare
        // checksums and request missing data from the peer.
        return true;
    }

    /**
     * Handle a snapshot request from a peer — return local data as snapshot.
     */
    public byte[] onSnapshot(String resourceType) {
        log.info("[harbor] distro snapshot requested for: {}", resourceType);
        try {
            if (RESOURCE_NAMING.equals(resourceType)) {
                Map<String, List<Instance>> data = serviceStorage.getAllInstanceData();
                return JSON.toJSONBytes(data);
            }
        } catch (Exception e) {
            log.error("[harbor] error building snapshot for: {}", resourceType, e);
        }
        return new byte[0];
    }

    // ========================================================================
    // Scheduled tasks
    // ========================================================================

    /**
     * Periodic verify: send local checksums to all peers.
     */
    private void runVerifyTask() {
        try {
            Set<ClusterMember> peers = clusterManager.allMembersExceptSelf();
            if (peers.isEmpty()) {
                return;
            }
            // Build naming checksums: serviceKey → instance count
            Map<String, String> namingChecksums = new java.util.HashMap<>();
            for (Map.Entry<String, Integer> e : serviceStorage.getVerifyChecksums().entrySet()) {
                namingChecksums.put(e.getKey(), String.valueOf(e.getValue()));
            }
            for (ClusterMember peer : peers) {
                try {
                    transport.syncVerify(peer.address(), RESOURCE_NAMING, namingChecksums);
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
                    log.info("[harbor] loading naming snapshot from {}", peer.address());
                    byte[] namingSnapshot = transport.getSnapshot(peer.address(), RESOURCE_NAMING);
                    if (namingSnapshot != null && namingSnapshot.length > 0) {
                        String json = new String(namingSnapshot, StandardCharsets.UTF_8);
                        Map<String, List<Instance>> data =
                                JSON.parseObject(json, new TypeReference<>() {});
                        serviceStorage.applySnapshot(data);
                        log.info("[harbor] naming snapshot loaded from {}", peer.address());
                        break;
                    }
                } catch (Exception e) {
                    log.warn("[harbor] load naming from {} failed: {}", peer.address(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("[harbor] load task error", e);
            // Retry after delay
            if (running) {
                scheduler.schedule(this::runLoadTask,
                        distroConfig.getLoadDataRetryDelayMillis(), TimeUnit.MILLISECONDS);
            }
        }
    }

    // ========================================================================
    // Internal sync handlers
    // ========================================================================

    private void handleNamingSync(String resourceKey, String operation, byte[] content) {
        if (OP_DELETE.equals(operation)) {
            // Parse key format: "namespace@@group@@serviceName"
            String[] parts = resourceKey.split("@@");
            if (parts.length == 3) {
                // Remove all instances for this service
                // (simplified — a full impl would parse the specific instance)
                log.info("[harbor] distro delete naming: {}", resourceKey);
            }
            return;
        }
        // For CHANGE: parse the instance data and register it
        if (content != null && content.length > 0) {
            Instance instance = JSON.parseObject(
                    new String(content, StandardCharsets.UTF_8), Instance.class);
            String[] parts = resourceKey.split("@@");
            if (parts.length == 3 && instance != null) {
                // Distro-synced instances carry the remote node's connectionId;
                // pass null locally so they are not tied to a local connection.
                serviceStorage.registerInstance(parts[0], parts[1], parts[2], instance, null);
            }
        }
    }
}
