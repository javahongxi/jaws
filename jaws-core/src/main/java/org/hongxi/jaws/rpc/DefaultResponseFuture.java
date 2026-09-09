package org.hongxi.jaws.rpc;

import org.hongxi.jaws.exception.JawsErrorCode;
import org.hongxi.jaws.exception.JawsServiceException;

import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * Default {@link ResponseFuture} backed by a {@link CompletableFuture}.
 * <p>
 * The network layer completes this future via {@link #onSuccess(Response)} or
 * {@link #onFailure(Response)} when the server reply arrives. Callers may block
 * with {@link #getValue()} (which delegates to {@link CompletableFuture#get()})
 * or use {@link CompletableFuture#whenComplete} for async notification.
 * Timeout is managed externally by the transport layer's per-request timer.
 *
 * <p>Created by shenhongxi on 2020/8/23.
 */
public class DefaultResponseFuture extends CompletableFuture<Response> implements ResponseFuture {

    private final Request request;
    private final int timeout;
    /** Why this future was cancelled; set by {@link #cancel(String, int)}. */
    private volatile String cancellationReason;
    /** Error code to report for the cancellation; a timeout is not a generic failure. */
    private volatile int cancellationErrorCode = JawsErrorCode.SERVICE_DEFAULT;

    public DefaultResponseFuture(Request request, int timeout) {
        this.request = request;
        this.timeout = timeout;
    }

    @Override
    public void onSuccess(Response response) {
        complete(response);
    }

    @Override
    public void onFailure(Response response) {
        completeExceptionally(response.getThrowable());
    }

    @Override
    public Object getValue() {
        try {
            Response r = get();
            return r.getValue();
        } catch (CancellationException e) {
            throw cancellationFailure(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JawsServiceException(
                    this.getClass().getName() + " getValue interrupted: requestId="
                            + request.getRequestId(), e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new JawsServiceException(
                    cause != null ? cause.getMessage() : "unknown error", cause);
        }
    }

    @Override
    public Object getRawValue() {
        if (isDone() && !isCompletedExceptionally()) {
            Response r = getNow(null);
            return r != null ? r.getRawValue() : null;
        }
        return null;
    }

    @Override
    public Throwable getThrowable() {
        if (isDone() && isCompletedExceptionally()) {
            try {
                get();
            } catch (Throwable t) {
                Throwable cause = (t instanceof CompletionException ce) ? ce.getCause() : t;
                if (cause instanceof CancellationException) {
                    return cancellationFailure(cause);
                }
                return cause instanceof RuntimeException re ? re
                        : new JawsServiceException(
                                cause != null ? cause.getMessage() : "unknown error", cause);
            }
        }
        return null;
    }

    @Override
    public void cancel() {
        cancel(true);
    }

    @Override
    public void cancel(String reason) {
        cancel(reason, JawsErrorCode.SERVICE_DEFAULT);
    }

    @Override
    public void cancel(String reason, int errorCode) {
        this.cancellationReason = reason;
        this.cancellationErrorCode = errorCode;
        cancel(true);
    }

    /**
     * Describe a cancellation with the call it belongs to: the bare
     * {@link CancellationException} carries neither the request nor the reason,
     * so both {@link #getValue()} and {@link #getThrowable()} would otherwise
     * report a null message.
     */
    private JawsServiceException cancellationFailure(Throwable cause) {
        String reason = cancellationReason != null ? cancellationReason : "cancelled";
        return new JawsServiceException(reason + ": " + request.getInterfaceName() + "."
                + request.getMethodName() + ", requestId=" + request.getRequestId()
                + ", timeout=" + timeout + "ms", cause, cancellationErrorCode);
    }

    @Override
    public boolean isSuccess() {
        return isDone() && !isCompletedExceptionally();
    }

    @Override
    public long getRequestId() {
        return request.getRequestId();
    }

    @Override
    public int getTimeout() {
        return timeout;
    }

    @Override
    public Map<String, String> getAttachments() {
        return Map.of();
    }

    @Override
    public void setAttachment(String key, String value) {
        // no-op: attachments not used on client-side future
    }

    @Override
    public long getProcessTime() {
        return 0;
    }

    @Override
    public byte getSerializationNumber() {
        return 0;
    }

    @Override
    public void setSerializationNumber(byte number) {
        // no-op: client-side future is never encoded
    }
}
