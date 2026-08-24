package org.hongxi.jaws.transport.http2;

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
    public static final String CONTENT_TYPE = "application/jaws";
    public static final String HEADER_CONTENT_TYPE = "content-type";

    /** Carries the Jaws Serialization SPI name (hessian2/fastjson2/protostuff). */
    public static final String HEADER_SERIALIZATION = "x-jaws-serialization";

    /**
     * Carries the streaming mode: "unary", "server", "client", or "bidi".
     * Absent or "unary" means traditional request-response.
     *
     * @see StreamType
     */
    public static final String HEADER_STREAMING = "x-jaws-streaming";

    /** Request path for all Jaws RPC invocations; routing is done inside the payload. */
    public static final String PATH = "/jaws/rpc";

    public static final String STATUS_OK = "200";
    public static final String STATUS_BAD_REQUEST = "400";
    public static final String STATUS_INTERNAL_ERROR = "500";
}
