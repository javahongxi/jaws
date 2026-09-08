package org.hongxi.jaws.wire;

import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.exception.JawsBizException;
import org.hongxi.jaws.rpc.URL;

import java.util.Random;
import java.util.Set;

/**
 * gRPC client retry policy with exponential backoff and jitter.
 * <p>
 * Decides which failures are retryable and computes the delay before the
 * next attempt. The policy is configured via URL parameters:
 * <ul>
 *   <li>{@code retryMaxAttempts} — max total attempts (1 = no retry)</li>
 *   <li>{@code retryInitialBackoffMs} — delay before the first retry</li>
 *   <li>{@code retryMaxBackoffMs} — upper bound on delay</li>
 *   <li>{@code retryBackoffMultiplierPct} — exponential growth (200 = 2x)</li>
 *   <li>{@code retryJitterPct} — random jitter factor (20 = ±20%)</li>
 * </ul>
 * <p>
 * Retryable gRPC status codes follow the gRPC retry spec (gRFC A6):
 * {@code UNAVAILABLE}, {@code RESOURCE_EXHAUSTED}. Connection-level failures
 * (channel not active, stream write failure) are also retryable.
 *
 * @author shenhongxi
 * @see <a href="https://github.com/grpc/proposal/blob/master/A6-client-retries.md">gRFC A6</a>
 */
public final class WireRetryPolicy {

    /** gRPC status codes eligible for retry (gRFC A6 non-idempotent set). */
    private static final Set<Integer> RETRYABLE_STATUSES = Set.of(
            WireConstants.STATUS_UNAVAILABLE,
            WireConstants.STATUS_RESOURCE_EXHAUSTED
    );

    private final int maxAttempts;
    private final long initialBackoffMs;
    private final long maxBackoffMs;
    private final double multiplier;
    private final double jitterFraction;
    private final Random random = new Random();

    /**
     * Build a retry policy from URL parameters.
     *
     * @return the policy, or {@code null} when retries are disabled (maxAttempts ≤ 1)
     */
    public static WireRetryPolicy fromUrl(URL url) {
        int maxAttempts = url.getIntParameter(UrlParam.Transport.RETRY_MAX_ATTEMPTS);
        if (maxAttempts <= 1) {
            return null;
        }
        long initialBackoff = url.getLongParameter(UrlParam.Transport.RETRY_INITIAL_BACKOFF_MS);
        long maxBackoff = url.getLongParameter(UrlParam.Transport.RETRY_MAX_BACKOFF_MS);
        int multiplierPct = url.getIntParameter(UrlParam.Transport.RETRY_BACKOFF_MULTIPLIER_PCT);
        int jitterPct = url.getIntParameter(UrlParam.Transport.RETRY_JITTER_PCT);
        return new WireRetryPolicy(maxAttempts, initialBackoff, maxBackoff,
                multiplierPct / 100.0, jitterPct / 100.0);
    }

    public WireRetryPolicy(int maxAttempts, long initialBackoffMs, long maxBackoffMs,
                           double multiplier, double jitterFraction) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.initialBackoffMs = Math.max(0, initialBackoffMs);
        this.maxBackoffMs = Math.max(initialBackoffMs, maxBackoffMs);
        this.multiplier = Math.max(1.0, multiplier);
        this.jitterFraction = Math.max(0, Math.min(1.0, jitterFraction));
    }

    /**
     * @return the maximum number of attempts (including the initial call)
     */
    public int maxAttempts() {
        return maxAttempts;
    }

    /**
     * Whether the given attempt number allows another retry.
     *
     * @param attempt the current attempt (0-based; 0 = initial call)
     * @return true if another retry is permitted
     */
    public boolean hasAnotherAttempt(int attempt) {
        return attempt + 1 < maxAttempts;
    }

    /**
     * Compute the backoff delay for the given retry attempt.
     *
     * @param retryIndex 0-based retry index (0 = first retry, after the initial call)
     * @return the delay in milliseconds
     */
    public long backoffDelayMs(int retryIndex) {
        // delay = initialBackoff * multiplier^retryIndex, capped at maxBackoff
        double delay = initialBackoffMs * Math.pow(multiplier, retryIndex);
        delay = Math.min(delay, maxBackoffMs);
        // Apply jitter: delay ± jitterFraction
        if (jitterFraction > 0) {
            delay = delay * (1.0 + jitterFraction * (2.0 * random.nextDouble() - 1.0));
        }
        return Math.max(0, (long) delay);
    }

    /**
     * Whether a gRPC status code is eligible for retry.
     *
     * @param grpcStatus the received gRPC status code
     * @return true if the status is retryable
     */
    public static boolean isRetryableStatus(int grpcStatus) {
        return RETRYABLE_STATUSES.contains(grpcStatus);
    }

    /**
     * Whether a transport-level failure is eligible for retry.
     * Connection errors, channel closures, and write failures are retryable.
     * Business exceptions are not.
     *
     * @param cause the failure cause
     * @return true if the failure is retryable
     */
    public static boolean isRetryableFailure(Throwable cause) {
        if (cause == null) {
            return false;
        }
        // Business exceptions are never retryable
        if (cause instanceof JawsBizException) {
            return false;
        }
        // Walk the cause chain looking for known retryable patterns
        Throwable t = cause;
        while (t != null) {
            String name = t.getClass().getName();
            if (name.endsWith("ClosedChannelException")
                    || name.endsWith("ConnectException")
                    || name.endsWith("ConnectTimeoutException")
                    || name.endsWith("AnnotatedConnectException")
                    || t instanceof java.util.concurrent.RejectedExecutionException) {
                return true;
            }
            if (t instanceof org.hongxi.jaws.exception.JawsServiceException jse) {
                // UNAVAILABLE-level failures are retryable
                String msg = jse.getMessage();
                if (msg != null && msg.contains("retryable")) {
                    return true;
                }
                if (msg != null && msg.contains("not available")) {
                    return true;
                }
            }
            t = t.getCause() == t ? null : t.getCause();
        }
        return false;
    }
}
