package org.hongxi.jaws.rpc;

import org.hongxi.jaws.common.util.RpcUtils;
import org.hongxi.jaws.exception.JawsErrorCode;
import org.hongxi.jaws.exception.JawsServiceException;

import java.util.*;

/**
 * Default {@link ResponseFuture} implementing asynchronous waiting with timeout
 * support. {@link #getValue()} blocks on an intrinsic lock (via wait/notify) until the
 * network layer completes the future through {@link #onSuccess(Response)} or
 * {@link #onFailure(Response)}, or until the request timeout elapses, in which case the
 * future is canceled with a timeout exception. All state transitions are guarded by
 * the lock, making completion and waiting thread-safe; registered listeners are
 * notified after the state changes.
 *
 * <p>Created by shenhongxi on 2020/8/23.
 */
public class DefaultResponseFuture implements ResponseFuture {

    protected long createTime;
    protected Request request;
    protected int timeout;
    protected URL serverUrl;

    protected volatile FutureState state = FutureState.DOING;
    // Volatile: also read outside the lock by getRawValue()/getThrowable()/isSuccess()
    protected volatile Object result;
    protected volatile Throwable throwable;

    // Volatile: written under the lock in addListener, read outside the lock
    // in notifyListeners; after the state leaves DOING the list is never mutated again
    protected volatile List<FutureListener> listeners;

    public DefaultResponseFuture(Request request, int timeout, URL serverUrl) {
        this.createTime = System.currentTimeMillis();
        this.request = request;
        this.timeout = timeout;
        this.serverUrl = serverUrl;
    }

    @Override
    public void onSuccess(Response response) {
        this.result = response.getValue();
        done();
    }

    @Override
    public void onFailure(Response response) {
        this.throwable = response.getThrowable();
        done();
    }

    private void done() {
        synchronized (this) {
            if (!isDoing()) {
                return;
            }

            state = FutureState.DONE;
            notifyAll();
        }

        // Notify outside the lock to avoid blocking on listener callbacks
        notifyListeners();
    }

    @Override
    public Object getValue() {
        synchronized (this) {
            if (!isDoing()) {
                return getValueOrThrow();
            }

            if (timeout <= 0) {
                try {
                    wait();
                } catch (Exception e) {
                    cancel(new JawsServiceException(this.getClass().getName() +
                            " getValue InterruptedException : "
                            + RpcUtils.toString(request) +
                            " cost=" + (System.currentTimeMillis() - createTime), e));
                }

                return getValueOrThrow();
            }

            long waitTime = timeout - (System.currentTimeMillis() - createTime);

            if (waitTime > 0) {
                for (; ; ) {
                    try {
                        wait(waitTime);
                    } catch (InterruptedException ignore) {
                        Thread.currentThread().interrupt();
                    }

                    if (!isDoing()) {
                        break;
                    } else {
                        waitTime = timeout - (System.currentTimeMillis() - createTime);
                        if (waitTime <= 0) {
                            break;
                        }
                    }
                }
            }

            if (isDoing()) {
                cancelOnTimeout();
            }

            return getValueOrThrow();
        }
    }

    private Object getValueOrThrow() {
        if (throwable != null) {
            throw throwable instanceof RuntimeException re ? re :
                    new JawsServiceException(throwable.getMessage(), throwable);
        }
        return result;
    }

    private void cancelOnTimeout() {
        synchronized (this) {
            if (!isDoing()) {
                return;
            }

            state = FutureState.CANCELED;
            throwable = new JawsServiceException(
                    this.getClass().getName() +
                            " request timeout: serverPort=" + serverUrl.getHostPort()
                            + " " + RpcUtils.toString(request) +
                            " cost=" + (System.currentTimeMillis() - createTime),
                    JawsErrorCode.SERVICE_TIMEOUT);

            notifyAll();
        }

        notifyListeners();
    }

    @Override
    public Object getRawValue() {
        return result;
    }

    @Override
    public Throwable getThrowable() {
        return throwable;
    }

    @Override
    public void cancel() {
        Throwable e = new JawsServiceException(this.getClass().getName() +
                " task cancel: serverPort=" + serverUrl.getHostPort() + " "
                + RpcUtils.toString(request) +
                " cost=" + (System.currentTimeMillis() - createTime));
        cancel(e);
    }

    private void cancel(Throwable e) {
        synchronized (this) {
            if (!isDoing()) {
                return;
            }

            state = FutureState.CANCELED;
            throwable = e;
            notifyAll();
        }

        notifyListeners();
    }

    private void notifyListeners() {
        if (listeners != null) {
            for (FutureListener listener : listeners) {
                listener.onComplete(this);
            }
        }
    }

    @Override
    public void addListener(FutureListener listener) {
        Objects.requireNonNull(listener, "FutureListener is null");

        // Decide inside the lock, notify outside to avoid blocking on listener callbacks
        boolean notifyNow = false;
        synchronized (this) {
            if (!isDoing()) {
                notifyNow = true;
            } else {
                if (listeners == null) {
                    listeners = new ArrayList<>();
                }
                listeners.add(listener);
            }
        }

        if (notifyNow) {
            listener.onComplete(this);
        }
    }

    @Override
    public boolean isDone() {
        return state.isDoneState();
    }

    @Override
    public boolean isSuccess() {
        return isDone() && (throwable == null);
    }

    private boolean isDoing() {
        return state.isDoingState();
    }

    @Override
    public long getRequestId() {
        return this.request.getRequestId();
    }

    @Override
    public int getTimeout() {
        return timeout;
    }

    @Override
    public Map<String, String> getAttachments() {
        return Collections.emptyMap();
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
}
