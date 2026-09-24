package org.hongxi.jaws.wire;

import io.netty.util.AsciiString;

/**
 * Wire protocol constants for the gRPC line format implemented by jaws-wire.
 * <p>
 * gRPC over HTTP/2 wire format:
 * <ol>
 *   <li>Path: {@code /{package.ServiceName}/{MethodName}}</li>
 *   <li>Content-Type: {@code application/grpc}</li>
 *   <li>Message frame: [1 byte compressed-flag][4 bytes big-endian length][payload]</li>
 *   <li>Request end: DATA frame with END_STREAM</li>
 *   <li>Response end: Trailers HEADERS frame (END_STREAM) carrying grpc-status / grpc-message</li>
 * </ol>
 *
 * @author shenhongxi
 */
public final class WireConstants {

    private WireConstants() {
    }

    /**
     * Header names/values are precomputed {@link AsciiString}s:
     * HPACK encode/decode and {@code DefaultHttp2Headers} lookups then stay on
     * the byte-based fast path (precomputed hash, same-type compares) instead
     * of paying String↔AsciiString conversions per frame.
     */
    public static final AsciiString CONTENT_TYPE_GRPC = AsciiString.of("application/grpc");
    /** gRPC content type header value. */
    public static final AsciiString HEADER_CONTENT_TYPE = AsciiString.of("content-type");

    /** gRPC frame header: 1 byte compressed flag + 4 bytes message length. */
    public static final int GRPC_HEADER_SIZE = 5;

    /**
     * SETTINGS_INITIAL_WINDOW_SIZE advertised by both wire endpoints (8 MiB,
     * aligned with Dubbo TripleConfig): the HTTP/2 protocol default 64 KiB
     * throttles bulk streams on any path with non-trivial RTT.
     */
    public static final int INITIAL_WINDOW_SIZE = 8 * 1024 * 1024;
    public static final byte NOT_COMPRESSED = 0;
    public static final byte COMPRESSED = 1;

    // grpc-status codes (used in trailers)
    public static final int STATUS_OK = 0;
    public static final int STATUS_CANCELED = 1;
    public static final int STATUS_UNKNOWN = 2;
    public static final int STATUS_INVALID_ARGUMENT = 3;
    public static final int STATUS_DEADLINE_EXCEEDED = 4;
    public static final int STATUS_NOT_FOUND = 5;
    public static final int STATUS_ALREADY_EXISTS = 6;
    public static final int STATUS_PERMISSION_DENIED = 7;
    public static final int STATUS_RESOURCE_EXHAUSTED = 8;
    public static final int STATUS_FAILED_PRECONDITION = 9;
    public static final int STATUS_ABORTED = 10;
    public static final int STATUS_OUT_OF_RANGE = 11;
    public static final int STATUS_UNIMPLEMENTED = 12;
    public static final int STATUS_INTERNAL = 13;
    public static final int STATUS_UNAVAILABLE = 14;
    public static final int STATUS_DATA_LOSS = 15;
    public static final int STATUS_UNAUTHENTICATED = 16;

    // Trailer header names
    public static final AsciiString GRPC_STATUS = AsciiString.of("grpc-status");
    public static final AsciiString GRPC_MESSAGE = AsciiString.of("grpc-message");
    public static final AsciiString GRPC_ENCODING = AsciiString.of("grpc-encoding");
    /** Encodings the sender accepts on incoming messages (client request / server response). */
    public static final AsciiString GRPC_ACCEPT_ENCODING =
            AsciiString.of("grpc-accept-encoding");

    // Message compression encodings
    public static final String ENCODING_IDENTITY = "identity";
    public static final String ENCODING_GZIP = "gzip";

    /** HTTP/2 header required by the gRPC protocol to allow trailer-based status. */
    public static final AsciiString HEADER_TE = AsciiString.of("te");

    /**
     * Key for the connection-level identifier propagated via parent-channel
     * attribute ({@link io.netty.util.AttributeKey}).  When a parent channel
     * carries this attribute, its value is merged into the
     * {@link WireCallContext} attachments so that business handlers can
     * identify which physical connection a request arrived on.
     * <p>
     * This is essential for protocols like Nacos 2.x where multiple TCP
     * connections from the same client IP must be distinguished.
     */
    public static final String CONNECTION_ID = "x-wire-connection-id";

    /**
     * Call-context attachment carrying the peer host of the TCP connection a
     * request arrived on, as the transport saw it. No registration is needed to
     * get it, and no port: a source port is ephemeral and says nothing about who
     * the caller is. Unlike the {@code clientIp} a sender writes into its own
     * request body — which costs nothing to forge — this is the one peer identity
     * the caller does not control.
     */
    public static final String CONNECTION_PEER = "x-wire-connection-peer";

    public static final AsciiString TE_TRAILERS = AsciiString.of("trailers");
    public static final AsciiString HEADER_USER_AGENT = AsciiString.of("user-agent");
    public static final AsciiString USER_AGENT = AsciiString.of(userAgent());

    private static String userAgent() {
        String version = WireConstants.class.getPackage().getImplementationVersion();
        return "jaws-wire/" + (version != null ? version : "dev");
    }
}
