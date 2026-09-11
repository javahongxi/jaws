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
     * Tracks the last activity timestamp (epoch millis) per connection.
     * Updated on every inbound request from the client (unary or bi-stream).
     * The watchdog uses this to detect and close dead connections.
     */
    private final Map<String, Long> lastActiveTime = new ConcurrentHashMap<>();

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
        connections.put(connectionId,
                new ConnectionRecord(connectionId, clientIp, clientVersion, labels, pushSubject));
        lastActiveTime.put(connectionId, System.currentTimeMillis());
        log.info("[harbor] connection registered: id={}, clientIp={}, version={}",
                connectionId, clientIp, clientVersion);
    }

    /**
     * Remove a connection (on stream close or client disconnect).
     */
    public void remove(String connectionId) {
        ConnectionRecord removed = connections.remove(connectionId);
        lastActiveTime.remove(connectionId);
        if (removed != null) {
            removed.pushSubject.onCompleted();
            log.info("[harbor] connection removed: id={}", connectionId);
        }
    }

    /**
     * Update the last activity timestamp for a connection.
     * Called on every inbound request from the client.
     */
    public void touch(String connectionId) {
        lastActiveTime.put(connectionId, System.currentTimeMillis());
    }

    /**
     * Update the last activity timestamp for ALL connections from a given
     * client IP.  This is needed for unary requests (e.g. HealthCheckRequest)
     * where the stream-level channel is not directly associated with a
     * specific connectionId.  The {@code connectionIdByClientIp} map in
     * {@code HarborServer} can be overwritten when the same client IP opens
     * multiple connections (Nacos client creates separate connections for
     * naming, config, etc.), so a map lookup would touch the wrong
     * connection and let the real one go stale.
     */
    public void touchByClientIp(String clientIp) {
        if (clientIp == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (ConnectionRecord record : connections.values()) {
            if (clientIp.equals(record.clientIp())) {
                lastActiveTime.put(record.connectionId(), now);
            }
        }
    }

    /**
     * Remove connections whose last activity exceeds the timeout.
     * Called by the periodic watchdog to clean up dead connections
     * (e.g. half-open TCP after client process killed).
     *
     * @param timeoutMs the inactivity timeout in milliseconds
     * @return list of removed connection records (caller should deregister instances)
     */
    public List<ConnectionRecord> removeStaleConnections(long timeoutMs) {
        long now = System.currentTimeMillis();
        List<ConnectionRecord> stale = new ArrayList<>();
        for (Map.Entry<String, Long> entry : lastActiveTime.entrySet()) {
            if (now - entry.getValue() > timeoutMs) {
                String connId = entry.getKey();
                ConnectionRecord removed = connections.remove(connId);
                lastActiveTime.remove(connId);
                if (removed != null) {
                    removed.pushSubject.onCompleted();
                    stale.add(removed);
                    log.info("[harbor] stale connection removed: id={}, clientIp={}, inactive={}ms",
                            connId, removed.clientIp(), now - entry.getValue());
                }
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
     * A single client connection record.
     */
    public record ConnectionRecord(
            String connectionId,
            String clientIp,
            String clientVersion,
            Map<String, String> labels,
            StreamSubject<Message> pushSubject
    ) {}
}
