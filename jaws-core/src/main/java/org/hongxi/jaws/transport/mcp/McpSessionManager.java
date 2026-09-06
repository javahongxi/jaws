package org.hongxi.jaws.transport.mcp;

import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages MCP session lifecycle for the Streamable HTTP transport.
 * <p>
 * Each MCP client connection creates a session identified by a UUID carried
 * in the {@code Mcp-Session-Id} HTTP header. A session tracks:
 * <ul>
 *   <li>Initialization state (capabilities negotiated during handshake)</li>
 *   <li>The optional SSE listening stream (GET /mcp) for server-initiated
 *       notifications pushed to the client</li>
 * </ul>
 * <p>
 * Thread-safe: sessions can be created, looked up, and removed concurrently.
 *
 * @author shenhongxi
 */
public class McpSessionManager {
    private static final Logger log = LoggerFactory.getLogger(McpSessionManager.class);

    /** Header name for MCP session identification. */
    public static final String MCP_SESSION_ID = "Mcp-Session-Id";
    public static final String LAST_EVENT_ID = "Last-Event-ID";
    public static final String PROTOCOL_VERSION = "MCP-Protocol-Version";

    /** The latest MCP protocol version this server supports. */
    public static final String SUPPORTED_PROTOCOL_VERSION = "2025-03-26";

    private final ConcurrentHashMap<String, McpSession> sessions = new ConcurrentHashMap<>();

    /**
     * Represents an individual MCP session.
     */
    public static class McpSession {
        private final String id;
        private volatile boolean initialized;
        private volatile String clientProtocolVersion;

        // The SSE listening stream channel (from GET /mcp), if any
        private volatile ChannelHandlerContext listeningCtx;

        McpSession(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }

        public boolean isInitialized() {
            return initialized;
        }

        public void setInitialized(boolean initialized) {
            this.initialized = initialized;
        }

        public String getClientProtocolVersion() {
            return clientProtocolVersion;
        }

        public void setClientProtocolVersion(String clientProtocolVersion) {
            this.clientProtocolVersion = clientProtocolVersion;
        }

        public ChannelHandlerContext getListeningCtx() {
            return listeningCtx;
        }

        public void setListeningCtx(ChannelHandlerContext listeningCtx) {
            this.listeningCtx = listeningCtx;
        }
    }

    /**
     * Create a new session and return it.
     *
     * @return the newly created session
     */
    public McpSession createSession() {
        String id = UUID.randomUUID().toString();
        McpSession session = new McpSession(id);
        sessions.put(id, session);
        log.debug("MCP session created: {}", id);
        return session;
    }

    /**
     * Look up a session by ID.
     *
     * @param sessionId the session ID from the Mcp-Session-Id header
     * @return the session, or null if not found
     */
    public McpSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    /**
     * Remove a session.
     *
     * @param sessionId the session ID to remove
     * @return the removed session, or null if not found
     */
    public McpSession removeSession(String sessionId) {
        McpSession removed = sessions.remove(sessionId);
        if (removed != null) {
            log.debug("MCP session removed: {}", sessionId);
            // Close the listening stream if active
            ChannelHandlerContext ctx = removed.getListeningCtx();
            if (ctx != null && ctx.channel().isActive()) {
                ctx.close();
            }
        }
        return removed;
    }

    /**
     * Send a server-initiated message to a session's listening stream (if connected).
     *
     * @param sessionId the target session
     * @param message   the JSON-RPC message to push
     * @return true if the message was sent, false if no listening stream or session not found
     */
    public boolean pushToSession(String sessionId, McpMessageCodec.Message message) {
        McpSession session = sessions.get(sessionId);
        if (session == null) {
            return false;
        }
        ChannelHandlerContext ctx = session.getListeningCtx();
        if (ctx == null || !ctx.channel().isActive()) {
            return false;
        }
        String json = McpMessageCodec.encode(message);
        SseEncoder.writeFrame(ctx, json, sessionId);
        return true;
    }

    /**
     * @return the number of active sessions
     */
    public int sessionCount() {
        return sessions.size();
    }

    /**
     * Close all sessions. Called during server shutdown.
     */
    public void closeAll() {
        for (Map.Entry<String, McpSession> entry : sessions.entrySet()) {
            McpSession session = entry.getValue();
            ChannelHandlerContext ctx = session.getListeningCtx();
            if (ctx != null && ctx.channel().isActive()) {
                ctx.close();
            }
        }
        sessions.clear();
        log.info("All MCP sessions closed, count={}", sessionCount());
    }
}
