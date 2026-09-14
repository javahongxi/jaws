package org.hongxi.jaws.harbor;

import com.google.protobuf.Message;
import org.hongxi.jaws.harbor.proto.Payload;
import org.hongxi.jaws.transport.StreamSubject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Manages client connections for the Nacos-compatible gRPC protocol.
 * <p>
 * Each nacos-client connection goes through two phases:
 * <ol>
 *   <li><b>ServerCheck</b> — a unary {@code Request.request} call that
 *       returns a {@code connectionId}</li>
 *   <li><b>BiStream setup</b> — the client opens a
 *       {@code BiRequestStream.requestBiStream} bidi stream and sends a
 *       {@code ConnectionSetupRequest} with client metadata</li>
 * </ol>
 * After setup, the connection is "registered" and can receive server push
 * notifications (e.g. {@code NotifySubscriberRequest}) via the push subject.
 *
 * @author shenhongxi
 */
public class ConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(ConnectionManager.class);

    private final Map<String, ConnectionRecord> connections = new ConcurrentHashMap<>();

    /**
     * Per-connection client session (primary store for Distro sync).
     * Keyed by connectionId, same key space as {@link #connections}.
     */
    private final Map<String, ClientSession> clientSessions = new ConcurrentHashMap<>();

    /**
     * Register a connection after the client sends ConnectionSetupRequest
     * via the BiRequestStream.
     *
     * @param connectionId   the ID assigned during ServerCheck
     * @param clientIp       the client's real IP (from Payload metadata)
     * @param clientVersion  the client version string
     * @param labels         client labels (module, source, appName, etc.)
     * @param pushSubject    the subject used to push server→client messages
     */
    public void register(String connectionId, String clientIp, String clientVersion,
                         Map<String, String> labels, StreamSubject<Message> pushSubject) {
        connections.put(connectionId, new ConnectionRecord(connectionId, clientIp, clientVersion,
                labels, pushSubject, new AtomicLong(System.currentTimeMillis())));
        ClientSession session = new ClientSession(connectionId, true);
        clientSessions.put(connectionId, session);
        log.info("[harbor] connection registered: id={}, clientIp={}, version={}",
                connectionId, clientIp, clientVersion);
    }

    /**
     * Remove a connection (on stream close or client disconnect).
     */
    public void remove(String connectionId) {
        ConnectionRecord removed = connections.remove(connectionId);
        clientSessions.remove(connectionId);
        if (removed != null) {
            removed.pushSubject.onCompleted();
            log.info("[harbor] connection removed: id={}", connectionId);
        }
    }

    /**
     * Stamp a connection as just active. Called on every inbound request from the
     * client (unary or bi-stream).
     * <p>
     * An id this node does not hold — notably the id of a {@code synced} client,
     * whose TCP connection lives on another node — is ignored: liveness is a
     * shard-local fact, so the clock rides on the connection record and is only
     * ever set here, never inferred from replicated data.
     */
    public void touch(String connectionId) {
        ConnectionRecord record = connections.get(connectionId);
        if (record != null) {
            record.touch();
        }
    }

    /**
     * Remove the LIVENESS layer (connection record) of connections whose last
     * activity exceeds the timeout.  Called by the periodic watchdog to detect
     * dead connections (e.g. half-open TCP after the client process was killed).
     * <p>
     * Deliberately does NOT evict the {@link ClientSession}: closing the
     * connection is {@link ConnectionLifecycle#cleanup}'s job — it snapshots
     * the session's data (which requires the session to still exist), removes
     * subscribers/instances and propagates the Distro DELETE before evicting
     * it.  Removing the session here would blind the closure transaction.
     *
     * @param timeoutMs the inactivity timeout in milliseconds
     * @return list of removed connection records (caller must run the full
     *         closure for each)
     */
    public List<ConnectionRecord> removeStaleConnections(long timeoutMs) {
        long now = System.currentTimeMillis();
        List<ConnectionRecord> stale = new ArrayList<>();
        for (ConnectionRecord record : connections.values()) {
            if (!record.isStale(now, timeoutMs)) {
                continue;
            }
            ConnectionRecord removed = connections.remove(record.connectionId());
            if (removed != null) {
                removed.pushSubject.onCompleted();
                stale.add(removed);
                log.info("[harbor] stale connection removed: id={}, clientIp={}, inactive={}ms",
                        removed.connectionId(), removed.clientIp(), now - removed.lastActive());
            }
        }
        return stale;
    }

    /**
     * Push a Payload message to a specific connection via its BiStream.
     *
     * @return true if the push was enqueued, false if the connection is not found
     */
    public boolean pushToConnection(String connectionId, Payload payload) {
        ConnectionRecord record = connections.get(connectionId);
        if (record == null) {
            return false;
        }
        record.pushSubject.onNext(payload);
        return true;
    }

    /**
     * @return the number of active connections
     */
    public int size() {
        return connections.size();
    }

    /**
     * @return all active connection records (read-only view)
     */
    public Collection<ConnectionRecord> allConnections() {
        return connections.values();
    }

    /**
     * Get the {@link ClientSession} for a specific connection.
     *
     * @param connectionId the connection ID
     * @return the client session, or {@code null} if not found
     */
    public ClientSession getClientSession(String connectionId) {
        return clientSessions.get(connectionId);
    }

    /**
     * Put a {@link ClientSession} directly (used by Distro sync to install
     * a synced client session on the receiving node).
     */
    public void putClientSession(String connectionId, ClientSession session) {
        clientSessions.put(connectionId, session);
    }

    /**
     * Remove a {@link ClientSession} without affecting the connection record.
     * Used by Distro DELETE to clean up synced (non-native) client sessions.
     */
    public void removeClientSession(String connectionId) {
        clientSessions.remove(connectionId);
    }

    /**
     * @return all client sessions (read-only view)
     */
    public Collection<ClientSession> allClientSessions() {
        return clientSessions.values();
    }

    /**
     * @return all native (non-synced) client sessions for Distro verify/sync
     */
    public List<ClientSession> allNativeClientSessions() {
        return clientSessions.values().stream()
                .filter(ClientSession::isNativeClient)
                .collect(Collectors.toList());
    }

    /**
     * A single client connection record — including its own activity clock.
     * <p>
     * The clock lives here (as a mutable carrier inside an immutable record)
     * rather than in a parallel map so that the liveness layer cannot drift out
     * of step with the connection registry: there is exactly one place to add
     * and one to remove.  This mirrors Nacos, where the timestamp is a field of
     * {@code Connection} and {@code ConnectionManager.refreshActiveTime()} just
     * delegates to {@code connection.freshActiveTime()}.
     */
    public record ConnectionRecord(
            String connectionId,
            String clientIp,
            String clientVersion,
            Map<String, String> labels,
            StreamSubject<Message> pushSubject,
            AtomicLong lastActiveTime
    ) {
        /** Stamp as active now; called from the transport thread serving the request. */
        void touch() {
            lastActiveTime.set(System.currentTimeMillis());
        }

        /** @return the epoch millis of the last observed activity. */
        long lastActive() {
            return lastActiveTime.get();
        }

        /** @return true when nothing has been seen on this connection for {@code timeoutMs}. */
        boolean isStale(long nowMillis, long timeoutMs) {
            return nowMillis - lastActiveTime.get() > timeoutMs;
        }
    }
}
