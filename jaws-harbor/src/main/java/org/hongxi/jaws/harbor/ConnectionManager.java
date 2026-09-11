package org.hongxi.jaws.harbor;

import com.google.protobuf.Message;
import org.hongxi.jaws.harbor.proto.Payload;
import org.hongxi.jaws.transport.StreamSubject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        log.info("[harbor] connection registered: id={}, clientIp={}, version={}",
                connectionId, clientIp, clientVersion);
    }

    /**
     * Remove a connection (on stream close or client disconnect).
     */
    public void remove(String connectionId) {
        ConnectionRecord removed = connections.remove(connectionId);
        if (removed != null) {
            removed.pushSubject.onCompleted();
            log.info("[harbor] connection removed: id={}", connectionId);
        }
    }

    /**
     * @return the connection record, or null if not registered
     */
    public ConnectionRecord getConnection(String connectionId) {
        return connections.get(connectionId);
    }

    /**
     * @return true if the connection is registered and active
     */
    public boolean isRegistered(String connectionId) {
        return connections.containsKey(connectionId);
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
