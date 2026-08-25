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

    /** HTTP/2 pseudo-headers used in gRPC requests. */
    public static final CharSequence HEADER_METHOD = ":method";
    public static final CharSequence HEADER_SCHEME = ":scheme";
    public static final CharSequence HEADER_PATH = ":path";
    public static final CharSequence HEADER_AUTHORITY = ":authority";
}
