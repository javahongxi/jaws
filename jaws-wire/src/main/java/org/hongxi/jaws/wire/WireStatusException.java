package org.hongxi.jaws.wire;

import com.google.rpc.Status;
import org.hongxi.jaws.exception.JawsServiceException;

/**
 * A gRPC failure surfaced to the caller, carrying the numeric {@code grpc-status}
 * code and — when the server sent {@code grpc-status-details-bin} — the decoded
 * {@link Status} (code, message, and arbitrary {@link com.google.protobuf.Any}
 * detail messages).
 * <p>
 * Extends {@link JawsServiceException} so existing callers that catch the base
 * type (or {@code RuntimeException}) are unaffected; callers that want the rich
 * gRPC error model downcast to this type and read {@link #getStatusDetails()}.
 * <p>
 * This closes the loop the client used to leave open: the unary response handler
 * already parsed {@code grpc-status-details-bin} but discarded it ("reserved for
 * future use"); it is now carried here, and the streaming/bidi handlers parse it
 * too.
 *
 * @author shenhongxi
 */
public class WireStatusException extends JawsServiceException {

    private static final long serialVersionUID = 1L;

    private final int grpcStatus;
    private final Status statusDetails;

    public WireStatusException(String message, int grpcStatus, Status statusDetails) {
        super(message);
        this.grpcStatus = grpcStatus;
        this.statusDetails = statusDetails;
    }

    public WireStatusException(String message, int jawsErrorCode, int grpcStatus, Status statusDetails) {
        super(message, jawsErrorCode);
        this.grpcStatus = grpcStatus;
        this.statusDetails = statusDetails;
    }

    /** @return the numeric gRPC status code (e.g. {@code 5} = NOT_FOUND). */
    public int getGrpcStatus() {
        return grpcStatus;
    }

    /**
     * @return the decoded {@code grpc-status-details-bin} Status including any
     *         {@link com.google.protobuf.Any} details, or {@code null} when the
     *         server sent no rich details.
     */
    public Status getStatusDetails() {
        return statusDetails;
    }
}
