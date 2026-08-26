package org.hongxi.jaws.wire;

import org.hongxi.jaws.exception.JawsBizException;
import org.hongxi.jaws.exception.JawsErrorCode;
import org.hongxi.jaws.exception.JawsServiceException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link WireStatus}: grpc-status mapping from jaws failures,
 * grpc-timeout header encode/decode, and the client-side exception factory.
 *
 * @author shenhongxi
 */
class WireStatusTest {

    @Test
    void jawsTimeoutMapsToDeadlineExceeded() {
        JawsServiceException e = new JawsServiceException("timeout",
                JawsErrorCode.SERVICE_TIMEOUT);
        assertEquals(WireStatus.STATUS_DEADLINE_EXCEEDED, WireStatus.fromThrowable(e));
    }

    @Test
    void methodNotFoundMapsToNotFound() {
        JawsServiceException e = new JawsServiceException("no method",
                JawsErrorCode.SERVICE_METHOD_NOT_FOUND);
        assertEquals(WireConstants.STATUS_NOT_FOUND, WireStatus.fromThrowable(e));
    }

    @Test
    void bizExceptionMapsToUnknown() {
        assertEquals(WireConstants.STATUS_UNKNOWN,
                WireStatus.fromThrowable(new JawsBizException("business error")));
    }

    @Test
    void completionTimeoutMapsToDeadlineExceeded() {
        // The shape thrown by CompletableFuture.orTimeout → join()
        CompletionException e = new CompletionException(
                new java.util.concurrent.TimeoutException());
        assertEquals(WireStatus.STATUS_DEADLINE_EXCEEDED, WireStatus.fromThrowable(e));
    }

    @Test
    void wrappedJawsExceptionIsUnwrapped() {
        RuntimeException wrapped = new RuntimeException("provider error",
                new JawsServiceException("timeout", JawsErrorCode.SERVICE_TIMEOUT));
        assertEquals(WireStatus.STATUS_DEADLINE_EXCEEDED, WireStatus.fromThrowable(wrapped));
    }

    @Test
    void connectFailureMapsToUnavailable() {
        assertEquals(WireStatus.STATUS_UNAVAILABLE,
                WireStatus.fromThrowable(new java.net.ConnectException("refused")));
    }

    @Test
    void genericFailureMapsToInternal() {
        assertEquals(WireConstants.STATUS_INTERNAL,
                WireStatus.fromThrowable(new RuntimeException("boom")));
    }

    @Test
    void timeoutHeaderRoundTrip() {
        assertEquals("5S", WireStatus.encodeTimeout(5000));
        assertEquals("1500m", WireStatus.encodeTimeout(1500));
        assertEquals(5000, WireStatus.decodeTimeout("5S"));
        assertEquals(1500, WireStatus.decodeTimeout("1500m"));
        assertEquals(2 * 60_000, WireStatus.decodeTimeout("2M"));
        assertEquals(3 * 3_600_000, WireStatus.decodeTimeout("3H"));
        assertEquals(1, WireStatus.decodeTimeout("1000u"));
    }

    @Test
    void malformedTimeoutHeaderReturnsMinusOne() {
        assertEquals(-1, WireStatus.decodeTimeout(null));
        assertEquals(-1, WireStatus.decodeTimeout("5"));
        assertEquals(-1, WireStatus.decodeTimeout("abc"));
        assertEquals(-1, WireStatus.decodeTimeout("5x"));
    }

    @Test
    void deadlineExceptionCarriesTimeoutErrorCode() {
        JawsServiceException e = (JawsServiceException) WireStatus.toException(
                WireStatus.STATUS_DEADLINE_EXCEEDED, "too slow");
        assertEquals(JawsErrorCode.SERVICE_TIMEOUT, e.getErrorCode());
        assertTrue(e.getMessage().contains("DEADLINE_EXCEEDED"));
    }

    @Test
    void unavailableExceptionIsFlaggedRetryable() {
        JawsServiceException e = (JawsServiceException) WireStatus.toException(
                WireStatus.STATUS_UNAVAILABLE, "down");
        assertTrue(e.getMessage().contains("retryable"));
        assertTrue(WireStatus.isRetryable(WireStatus.STATUS_UNAVAILABLE));
        assertFalse(WireStatus.isRetryable(WireConstants.STATUS_INTERNAL));
    }

    @Test
    void orTimeoutExpiryMapsThroughFromThrowable() throws Exception {
        CompletableFuture<Object> f = new CompletableFuture<>();
        f.orTimeout(20, TimeUnit.MILLISECONDS);
        try {
            f.join();
        } catch (CompletionException e) {
            assertEquals(WireStatus.STATUS_DEADLINE_EXCEEDED, WireStatus.fromThrowable(e));
            return;
        }
        throw new AssertionError("expected CompletionException");
    }
}
