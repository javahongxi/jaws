package org.hongxi.jaws.wire;

/**
 * gRPC connectivity states as defined in gRFC A13.
 * <p>
 * A channel moves through these states during its lifecycle:
 * <pre>
 *   IDLE → CONNECTING → READY ⇄ TRANSIENT_FAILURE
 *     ↓                      ↓
 *   SHUTDOWN ←────────────── SHUTDOWN
 * </pre>
 *
 * <ul>
 *   <li>{@link #IDLE} — not attempting to connect; lazy connection on first RPC</li>
 *   <li>{@link #CONNECTING} — a connection attempt is in progress</li>
 *   <li>{@link #READY} — connected and ready to send RPCs</li>
 *   <li>{@link #TRANSIENT_FAILURE} — connection attempt failed; will retry</li>
 *   <li>{@link #SHUTDOWN} — the channel has been shut down</li>
 * </ul>
 *
 * @author shenhongxi
 * @see <a href="https://github.com/grpc/proposal/blob/master/A13-client-side-keepalives.md">gRFC A13</a>
 */
public enum WireConnectivityState {
    /** Not trying to connect; lazy connection on first RPC. */
    IDLE,

    /** A connection attempt is in progress. */
    CONNECTING,

    /** Connected and ready to send RPCs. */
    READY,

    /** Connection attempt failed; will retry with backoff. */
    TRANSIENT_FAILURE,

    /** The channel has been shut down; no further state transitions. */
    SHUTDOWN
}
