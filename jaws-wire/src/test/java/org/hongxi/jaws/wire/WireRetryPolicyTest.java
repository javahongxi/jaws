package org.hongxi.jaws.wire;

import org.hongxi.jaws.exception.JawsBizException;
import org.hongxi.jaws.exception.JawsServiceException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.nio.channels.ClosedChannelException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link WireRetryPolicy}: backoff computation, retryable
 * status classification, and retryable failure detection.
 *
 * @author shenhongxi
 */
class WireRetryPolicyTest {

    @Test
    void backoffGrowsExponentially() {
        WireRetryPolicy policy = new WireRetryPolicy(5, 100, 10000, 2.0, 0);
        assertEquals(100, policy.backoffDelayMs(0));
        assertEquals(200, policy.backoffDelayMs(1));
        assertEquals(400, policy.backoffDelayMs(2));
        assertEquals(800, policy.backoffDelayMs(3));
        assertEquals(1600, policy.backoffDelayMs(4));
    }

    @Test
    void backoffIsCappedAtMax() {
        WireRetryPolicy policy = new WireRetryPolicy(10, 100, 500, 2.0, 0);
        // 100 * 2^3 = 800 > 500 → capped at 500
        assertEquals(500, policy.backoffDelayMs(3));
        assertEquals(500, policy.backoffDelayMs(10));
    }

    @Test
    void jitterStaysWithinBounds() {
        WireRetryPolicy policy = new WireRetryPolicy(5, 100, 10000, 2.0, 0.5);
        for (int i = 0; i < 100; i++) {
            long delay = policy.backoffDelayMs(2); // base = 400
            // With 50% jitter: 400 * [0.5, 1.5] = [200, 600]
            assertTrue(delay >= 200 && delay <= 600,
                    "delay " + delay + " out of expected jitter range");
        }
    }

    @Test
    void hasAnotherAttemptRespectsMax() {
        WireRetryPolicy policy = new WireRetryPolicy(3, 100, 1000, 2.0, 0);
        assertTrue(policy.hasAnotherAttempt(0));
        assertTrue(policy.hasAnotherAttempt(1));
        assertFalse(policy.hasAnotherAttempt(2));
        assertFalse(policy.hasAnotherAttempt(3));
    }

    @Test
    void retryableStatusCodes() {
        assertTrue(WireRetryPolicy.isRetryableStatus(WireConstants.STATUS_UNAVAILABLE));
        assertTrue(WireRetryPolicy.isRetryableStatus(WireConstants.STATUS_RESOURCE_EXHAUSTED));
        assertFalse(WireRetryPolicy.isRetryableStatus(WireConstants.STATUS_OK));
        assertFalse(WireRetryPolicy.isRetryableStatus(WireConstants.STATUS_INTERNAL));
        assertFalse(WireRetryPolicy.isRetryableStatus(WireConstants.STATUS_INVALID_ARGUMENT));
        assertFalse(WireRetryPolicy.isRetryableStatus(WireConstants.STATUS_UNIMPLEMENTED));
    }

    @Test
    void retryableFailures() {
        assertTrue(WireRetryPolicy.isRetryableFailure(new ClosedChannelException()));
        assertTrue(WireRetryPolicy.isRetryableFailure(new ConnectException("connection refused")));
        assertTrue(WireRetryPolicy.isRetryableFailure(new IOException(new ClosedChannelException())));
        assertTrue(WireRetryPolicy.isRetryableFailure(
                new JawsServiceException("gRPC UNAVAILABLE (retryable): server down")));
        assertTrue(WireRetryPolicy.isRetryableFailure(
                new JawsServiceException("Wire channel is not available: url=...")));
    }

    @Test
    void nonRetryableFailures() {
        assertFalse(WireRetryPolicy.isRetryableFailure(null));
        assertFalse(WireRetryPolicy.isRetryableFailure(new JawsBizException("business error")));
        assertFalse(WireRetryPolicy.isRetryableFailure(new IllegalArgumentException("bad arg")));
        assertFalse(WireRetryPolicy.isRetryableFailure(
                new JawsServiceException("gRPC INTERNAL: something broke")));
    }

    @Test
    void singleAttemptPolicy() {
        WireRetryPolicy policy = new WireRetryPolicy(1, 100, 1000, 2.0, 0);
        assertEquals(1, policy.maxAttempts());
        assertFalse(policy.hasAnotherAttempt(0));
    }
}
