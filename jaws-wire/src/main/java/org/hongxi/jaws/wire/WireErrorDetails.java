package org.hongxi.jaws.wire;

import com.google.rpc.Status;
import io.netty.handler.codec.http2.Http2Headers;

import java.util.Base64;

/**
 * Support for the gRPC Rich Error Model: the {@code grpc-status-details-bin}
 * trailer carries a base64-encoded, serialized {@link Status} protobuf
 * message with structured error details (code, message, and arbitrary
 * {@link com.google.protobuf.Any} detail messages).
 * <p>
 * Standard gRPC clients (Go, Python, Java) decode this trailer to extract
 * machine-readable error context beyond the plain {@code grpc-status} code
 * and {@code grpc-message} string.
 * <p>
 * Wire format: the trailer value is the standard base64 encoding (with padding)
 * of the serialized {@code google.rpc.Status} protobuf bytes.
 *
 * @author shenhongxi
 * @see <a href="https://grpc.github.io/grpc/core/md_doc_statuscodes.html">gRPC Status Codes</a>
 */
public final class WireErrorDetails {

    /** Trailer header carrying the rich error details. */
    public static final CharSequence GRPC_STATUS_DETAILS_BIN = "grpc-status-details-bin";

    private WireErrorDetails() {
    }

    /**
     * Build a {@link Status} protobuf from the given gRPC status code and message.
     *
     * @param grpcStatus the gRPC status code
     * @param message    the human-readable error message
     * @return the Status protobuf message
     */
    public static Status buildStatus(int grpcStatus, String message) {
        Status.Builder builder = Status.newBuilder().setCode(grpcStatus);
        if (message != null) {
            builder.setMessage(message);
        }
        return builder.build();
    }

    /**
     * Encode a {@link Status} as the base64 value for the
     * {@code grpc-status-details-bin} trailer.
     *
     * @param status the Status protobuf message
     * @return the base64-encoded serialized bytes
     */
    public static String encode(Status status) {
        return Base64.getEncoder().encodeToString(status.toByteArray());
    }

    /**
     * Decode the {@code grpc-status-details-bin} trailer value back into a
     * {@link Status} protobuf message.
     *
     * @param value the base64-encoded trailer value
     * @return the decoded Status, or {@code null} if the value is null or malformed
     */
    public static Status decode(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(value);
            return Status.parseFrom(bytes);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extract the rich error details from HTTP/2 trailers, if present.
     *
     * @param trailers the response trailers
     * @return the decoded Status, or {@code null} if not present
     */
    public static Status fromTrailers(Http2Headers trailers) {
        CharSequence value = trailers.get(GRPC_STATUS_DETAILS_BIN);
        if (value == null) {
            return null;
        }
        return decode(value.toString());
    }

    /**
     * Write the rich error details into HTTP/2 trailers.
     *
     * @param trailers   the trailers to write into
     * @param grpcStatus the gRPC status code
     * @param message    the error message
     */
    public static void writeToTrailers(Http2Headers trailers, int grpcStatus, String message) {
        Status status = buildStatus(grpcStatus, message);
        trailers.set(GRPC_STATUS_DETAILS_BIN, encode(status));
    }
}
