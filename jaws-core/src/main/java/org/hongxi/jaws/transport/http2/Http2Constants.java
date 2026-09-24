package org.hongxi.jaws.transport.http2;

import io.netty.util.AsciiString;

/**
 * Wire protocol constants for the Jaws HTTP/2 transport.
 * <p>
 * Each RPC invocation maps to one HTTP/2 stream: the client sends a HEADERS
 * frame followed by DATA frame(s) carrying the Jaws-serialized payload.
 * For unary calls, a single DATA frame with END_STREAM; for streaming calls,
 * multiple DATA frames with END_STREAM on the last one.
 * <p>
 * Unlike the gRPC wire format there is no 5-byte length prefix: HTTP/2 DATA
 * frame boundaries already delimit the message, and END_STREAM marks completion.
 *
 * @author shenhongxi
 */
public final class Http2Constants {

    private Http2Constants() {
    }

    /** Content type identifying Jaws RPC payloads (as opposed to application/grpc). */
    /**
     * Header names/values are precomputed {@link AsciiString}s: HPACK
     * encode/decode and {@code DefaultHttp2Headers} lookups stay on the
     * byte-based fast path (precomputed hash, same-type compares) instead of
     * paying String↔AsciiString conversions per frame. JFR on the http2
     * transport showed these conversions at ~15% of the consumer hot path.
     */
    public static final AsciiString CONTENT_TYPE = AsciiString.of("application/jaws");
    public static final AsciiString HEADER_CONTENT_TYPE = AsciiString.of("content-type");

    /** Carries the Jaws Serialization SPI name (hessian2/fastjson2/protostuff). */
    public static final AsciiString HEADER_SERIALIZATION = AsciiString.of("x-jaws-serialization");

    /**
     * Carries the streaming mode: "unary", "server", or "bidi".
     * Absent or "unary" means traditional request-response.
     *
     * @see StreamType
     */
    public static final String HEADER_STREAMING = "x-jaws-streaming";

    /** Request path for all Jaws RPC invocations; routing is done inside the payload. */
    public static final AsciiString PATH = AsciiString.of("/jaws/rpc");

    /** Health check endpoint path. */
    public static final String HEALTH_PATH = "/health";

    // ---- Metadata mirror headers (mirrored from payload for gateway visibility) ----

    /** Service interface fully-qualified name. */
    public static final AsciiString HEADER_INTERFACE = AsciiString.of("x-jaws-interface");

    /** Invocation method name. */
    public static final AsciiString HEADER_METHOD = AsciiString.of("x-jaws-method");

    /** Parameter signature descriptor. */
    public static final AsciiString HEADER_PARAM_DESC = AsciiString.of("x-jaws-param-desc");

    /** Service group. */
    public static final AsciiString HEADER_GROUP = AsciiString.of("x-jaws-group");

    /** Service version. */
    public static final AsciiString HEADER_VERSION = AsciiString.of("x-jaws-version");

    public static final String STATUS_OK = "200";
    public static final String STATUS_BAD_REQUEST = "400";
    public static final String STATUS_INTERNAL_ERROR = "500";
    /** Business thread pool is full and the request is rejected. */
    public static final String STATUS_SERVICE_UNAVAILABLE = "503";

    /**
     * SETTINGS_INITIAL_WINDOW_SIZE advertised by both HTTP/2 endpoints (8 MiB,
     * aligned with Dubbo TripleConfig): the protocol default 64 KiB throttles
     * bulk streams on any path with non-trivial RTT.
     */
    public static final int INITIAL_WINDOW_SIZE = 8 * 1024 * 1024;

}
