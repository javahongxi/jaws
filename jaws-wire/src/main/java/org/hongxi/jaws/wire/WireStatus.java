package org.hongxi.jaws.wire;

import org.hongxi.jaws.exception.JawsBizException;
import org.hongxi.jaws.exception.JawsErrorCode;
import org.hongxi.jaws.exception.JawsServiceException;

/**
 * Mapping between Jaws exceptions / error codes and gRPC status codes
 * ({@code grpc-status} trailers), plus the reverse mapping for the client side.
 * <p>
 * Wire-level semantics alignment: a standard gRPC client decides whether to
 * retry (UNAVAILABLE), report a deadline miss (DEADLINE_EXCEEDED), or surface
 * a business error (UNKNOWN / custom) based on the status code — so the code
 * jaws returns must reflect what actually happened, not default to INTERNAL.
 * <p>
 * Mapping rules (jaws → gRPC):
 * <ul>
 *   <li>{@code SERVICE_TIMEOUT} (40003) → DEADLINE_EXCEEDED (4)</li>
 *   <li>Connection / transport failures → UNAVAILABLE (14) — retryable</li>
 *   <li>{@code SERVICE_METHOD_NOT_FOUND} (40102) / unknown path → NOT_FOUND (5)</li>
 *   <li>Unsupported feature / method → UNIMPLEMENTED (12)</li>
 *   <li>{@link JawsBizException} → UNKNOWN (2) — business logic outcome, not a transport fault</li>
 *   <li>Everything else → INTERNAL (13)</li>
 * </ul>
 *
 * @author shenhongxi
 */
public final class WireStatus {

    private WireStatus() {
    }

    // ---- Extended grpc-status codes beyond the legacy set in WireConstants ----

    /** Deadline expired before the call completed. */
    public static final int STATUS_DEADLINE_EXCEEDED = 4;
    /** Some resource has been exhausted (e.g. thread pool / queue full). */
    public static final int STATUS_RESOURCE_EXHAUSTED = 8;
    /** The service is currently unavailable — retryable by standard clients. */
    public static final int STATUS_UNAVAILABLE = 14;

    /**
     * Map a provider-side failure to the grpc-status code a standard gRPC
     * client expects for that failure class.
     *
     * @param e the failure thrown while serving the call
     * @return the grpc-status code
     */
    public static int fromThrowable(Throwable e) {
        if (e == null) {
            return WireConstants.STATUS_INTERNAL;
        }
        if (e instanceof JawsBizException) {
            return WireConstants.STATUS_UNKNOWN;
        }
        if (isDeadlineExpired(e)) {
            return STATUS_DEADLINE_EXCEEDED;
        }
        JawsServiceException jse = asJawsServiceException(e);
        if (jse != null && jse.getErrorCode() == JawsErrorCode.SERVICE_TIMEOUT) {
            return STATUS_DEADLINE_EXCEEDED;
        }
        if (jse != null && jse.getErrorCode() == JawsErrorCode.SERVICE_METHOD_NOT_FOUND) {
            return WireConstants.STATUS_NOT_FOUND;
        }
        if (isConnectivityFailure(e)) {
            return STATUS_UNAVAILABLE;
        }
        return WireConstants.STATUS_INTERNAL;
    }

    /**
     * Map a grpc-status code received from a server to a failure hint for the
     * jaws client: standard gRPC clients retry on UNAVAILABLE, and DEADLINE
     * signals a timeout rather than a server fault.
     *
     * @param grpcStatus the status code from trailers
     * @return true if the failure is retryable per gRPC semantics
     */
    public static boolean isRetryable(int grpcStatus) {
        return grpcStatus == STATUS_UNAVAILABLE;
    }

    /**
     * @param grpcStatus the status code from trailers
     * @return true if the status means the deadline expired
     */
    public static boolean isDeadlineExceeded(int grpcStatus) {
        return grpcStatus == STATUS_DEADLINE_EXCEEDED;
    }

    /**
     * Build the exception surfaced to the jaws caller when the server reports
     * a non-OK grpc-status in trailers. The exception message carries the
     * status name so callers (and logs) can distinguish deadline / unavailable /
     * business failures without parsing raw codes.
     *
     * @param grpcStatus the status code from trailers
     * @param grpcMessage the grpc-message from trailers, may be null
     * @return the exception to fail the call with
     */
    public static RuntimeException toException(int grpcStatus, String grpcMessage) {
        String name = nameOf(grpcStatus);
        String detail = grpcMessage != null ? grpcMessage : "";
        if (isDeadlineExceeded(grpcStatus)) {
            return new JawsServiceException(
                    "gRPC " + name + ": " + detail, JawsErrorCode.SERVICE_TIMEOUT);
        }
        if (isRetryable(grpcStatus)) {
            return new JawsServiceException(
                    "gRPC " + name + " (retryable): " + detail, JawsErrorCode.SERVICE_DEFAULT);
        }
        return new JawsServiceException("gRPC " + name + ": " + detail);
    }

    /**
     * @param grpcStatus the status code
     * @return the canonical gRPC status name (e.g. {@code DEADLINE_EXCEEDED})
     */
    public static String nameOf(int grpcStatus) {
        return switch (grpcStatus) {
            case WireConstants.STATUS_OK -> "OK";
            case WireConstants.STATUS_CANCELLED -> "CANCELLED";
            case WireConstants.STATUS_UNKNOWN -> "UNKNOWN";
            case WireConstants.STATUS_NOT_FOUND -> "NOT_FOUND";
            case STATUS_DEADLINE_EXCEEDED -> "DEADLINE_EXCEEDED";
            case WireConstants.STATUS_UNIMPLEMENTED -> "UNIMPLEMENTED";
            case STATUS_RESOURCE_EXHAUSTED -> "RESOURCE_EXHAUSTED";
            case WireConstants.STATUS_INTERNAL -> "INTERNAL";
            case STATUS_UNAVAILABLE -> "UNAVAILABLE";
            default -> "UNKNOWN_STATUS_" + grpcStatus;
        };
    }

    // ---- grpc-timeout header encoding/decoding ----

    /** Trailer/header carrying the caller's deadline, e.g. {@code grpc-timeout: 1500m}. */
    public static final CharSequence GRPC_TIMEOUT = "grpc-timeout";

    /**
     * Encode a timeout as the gRPC timeout header value: 1-8 digits plus a
     * unit (Hour/Minute/Second/milliSecond/microsecond/nanosecond), e.g.
     * {@code 5S}, {@code 1500m}. Values under 1ms are clamped to 1m.
     *
     * @param timeoutMs the timeout in milliseconds
     * @return the header value
     */
    public static String encodeTimeout(long timeoutMs) {
        if (timeoutMs >= 1000 && timeoutMs % 1000 == 0) {
            return (timeoutMs / 1000) + "S";
        }
        return timeoutMs + "m";
    }

    /**
     * Parse a gRPC timeout header value into milliseconds.
     *
     * @param value the header value, e.g. {@code 5S} or {@code 1500m}
     * @return the timeout in milliseconds, or -1 if the value is malformed
     */
    public static long decodeTimeout(String value) {
        if (value == null || value.length() < 2) {
            return -1;
        }
        char unit = value.charAt(value.length() - 1);
        long amount;
        try {
            amount = Long.parseLong(value.substring(0, value.length() - 1));
        } catch (NumberFormatException e) {
            return -1;
        }
        return switch (unit) {
            case 'H' -> amount * 3_600_000;
            case 'M' -> amount * 60_000;
            case 'S' -> amount * 1_000;
            case 'm' -> amount;
            case 'u' -> amount / 1_000;
            case 'n' -> amount / 1_000_000;
            default -> -1;
        };
    }

    private static boolean isDeadlineExpired(Throwable e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof java.util.concurrent.TimeoutException) {
                return true;
            }
            t = t.getCause() == t ? null : t.getCause();
        }
        return false;
    }

    private static JawsServiceException asJawsServiceException(Throwable e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof JawsServiceException jse) {
                return jse;
            }
            t = t.getCause() == t ? null : t.getCause();
        }
        return null;
    }

    private static boolean isConnectivityFailure(Throwable e) {
        String name = e.getClass().getName();
        return name.endsWith("ConnectException")
                || name.endsWith("ConnectTimeoutException")
                || name.endsWith("ClosedChannelException")
                || name.endsWith("ConnectionResetException");
    }
}
