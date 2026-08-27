package org.hongxi.jaws.wire;

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

    /** gRPC content type header value. */
    public static final String CONTENT_TYPE_GRPC = "application/grpc";
    public static final CharSequence HEADER_CONTENT_TYPE = "content-type";

    /** gRPC frame header: 1 byte compressed flag + 4 bytes message length. */
    public static final int GRPC_HEADER_SIZE = 5;
    public static final byte NOT_COMPRESSED = 0;
    public static final byte COMPRESSED = 1;

    // grpc-status codes (used in trailers)
    public static final int STATUS_OK = 0;
    public static final int STATUS_CANCELLED = 1;
    public static final int STATUS_UNKNOWN = 2;
    public static final int STATUS_NOT_FOUND = 5;
    public static final int STATUS_UNIMPLEMENTED = 12;
    public static final int STATUS_INTERNAL = 13;

    // Trailer header names
    public static final CharSequence GRPC_STATUS = "grpc-status";
    public static final CharSequence GRPC_MESSAGE = "grpc-message";
    public static final CharSequence GRPC_ENCODING = "grpc-encoding";
    /** Encodings the sender accepts on incoming messages (client request / server response). */
    public static final CharSequence GRPC_ACCEPT_ENCODING = "grpc-accept-encoding";

    // Message compression encodings
    public static final String ENCODING_IDENTITY = "identity";
    public static final String ENCODING_GZIP = "gzip";
    /** Value advertised for grpc-accept-encoding when both encodings are supported. */
    public static final String ACCEPT_ENCODINGS = "identity,gzip";

    /** HTTP/2 header required by the gRPC protocol to allow trailer-based status. */
    public static final CharSequence HEADER_TE = "te";
    public static final CharSequence TE_TRAILERS = "trailers";
    public static final CharSequence HEADER_USER_AGENT = "user-agent";
    public static final String USER_AGENT = userAgent();

    /** HTTP/2 pseudo-headers used in gRPC requests. */
    public static final CharSequence HEADER_METHOD = ":method";
    public static final CharSequence HEADER_SCHEME = ":scheme";
    public static final CharSequence HEADER_PATH = ":path";
    public static final CharSequence HEADER_AUTHORITY = ":authority";

    private static String userAgent() {
        String version = WireConstants.class.getPackage().getImplementationVersion();
        return "jaws-wire/" + (version != null ? version : "dev");
    }
}
