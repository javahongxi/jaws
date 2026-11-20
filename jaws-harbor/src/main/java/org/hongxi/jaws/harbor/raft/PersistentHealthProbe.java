package org.hongxi.jaws.harbor.raft;

import org.hongxi.jaws.harbor.ClientSession;
import org.hongxi.jaws.harbor.ServiceStorage;
import org.hongxi.jaws.harbor.model.Instance;
import org.hongxi.jaws.harbor.model.ServiceKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Server-side health probing of persistent instances — the harbor counterpart
 * of Nacos's {@code HealthCheckTaskV2} + {@code TcpHealthCheckProcessor}: a
 * persistent instance has no connection whose liveness could stand in for its
 * health, so the registry itself TCP-connects to every registered address.
 * <p>
 * Leader-only: exactly one node probes, and a verdict flip is proposed as a
 * HEALTH command through Raft (Nacos's {@code PersistentHealthStatusSynchronizer}
 * shape), so every node flips the flag at the same log position instead of
 * each judging on its own. A follower that becomes leader picks the probing up
 * on its next tick; no hand-over state exists.
 *
 * @author shenhongxi
 */
public class PersistentHealthProbe {

    private static final Logger log = LoggerFactory.getLogger(PersistentHealthProbe.class);

    private static final long PROBE_INTERVAL_MS = 3000;
    private static final int CONNECT_TIMEOUT_MS = 1000;

    private final ServiceStorage serviceStorage;
    private final HarborRaftProtocol raftProtocol;
    private final RaftNode raftNode;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "harbor-persistent-probe");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService probePool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "harbor-persistent-probe-worker");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean running;

    /** One address to judge, with the flag value the last committed state holds. */
    private record ProbeTarget(ServiceKey key, String clientId, String ip, int port, boolean healthy) {
    }

    public PersistentHealthProbe(ServiceStorage serviceStorage,
                                 HarborRaftProtocol raftProtocol, RaftNode raftNode) {
        this.serviceStorage = serviceStorage;
        this.raftProtocol = raftProtocol;
        this.raftNode = raftNode;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        scheduler.scheduleAtFixedRate(this::tick, PROBE_INTERVAL_MS, PROBE_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
    }

    public void shutdown() {
        running = false;
        scheduler.shutdownNow();
        probePool.shutdownNow();
    }

    private void tick() {
        if (!running || !raftNode.isLeader()) {
            return;
        }
        List<ProbeTarget> targets = new ArrayList<>();
        for (ClientSession session : serviceStorage.allPersistentSessions()) {
            for (Map.Entry<ServiceKey, List<Instance>> entry : session.getAllPublishers().entrySet()) {
                for (Instance instance : entry.getValue()) {
                    targets.add(new ProbeTarget(entry.getKey(), session.getConnectionId(),
                            instance.getIp(), instance.getPort(), instance.isHealthy()));
                }
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        List<Callable<Boolean>> probes = new ArrayList<>();
        for (ProbeTarget target : targets) {
            probes.add(() -> tcpReachable(target.ip(), target.port()));
        }
        try {
            // Bound the whole round so a pile of dead addresses cannot stack ticks up.
            List<Future<Boolean>> verdicts = probePool.invokeAll(probes,
                    PROBE_INTERVAL_MS, TimeUnit.MILLISECONDS);
            for (int i = 0; i < targets.size(); i++) {
                ProbeTarget target = targets.get(i);
                boolean reachable = verdictOf(verdicts.get(i));
                if (reachable != target.healthy()) {
                    proposeFlip(target, reachable);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A cancelled (over-budget) probe counts as unreachable, like a refused connect. */
    private static boolean verdictOf(Future<Boolean> verdict) {
        try {
            return verdict.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private void proposeFlip(ProbeTarget target, boolean healthy) {
        Instance verdict = new Instance();
        verdict.setIp(target.ip());
        verdict.setPort(target.port());
        verdict.setHealthy(healthy);
        try {
            raftProtocol.write(NamingCommand.health(target.key().namespace(), target.key().group(),
                    target.key().name(), target.clientId(), verdict));
            log.info("[harbor] persistent probe flip committed: {}:{} healthy={}",
                    target.ip(), target.port(), healthy);
        } catch (Exception e) {
            // Leadership may have moved mid-tick; the next leader re-probes anyway.
            log.warn("[harbor] persistent probe flip not committed: {}:{} -> {}: {}",
                    target.ip(), target.port(), healthy, e.getMessage());
        }
    }

    private static boolean tcpReachable(String ip, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}