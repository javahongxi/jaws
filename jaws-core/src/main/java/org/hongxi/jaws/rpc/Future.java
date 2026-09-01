package org.hongxi.jaws.rpc;

/**
 * Represents the result of an asynchronous RPC invocation.
 * <p>
 * Provides methods to check completion status, retrieve the result or exception,
 * cancel the task, and register listeners for completion notification.
 *
 * @see ResponseFuture
 * @see FutureListener
 */
public interface Future {

    /**
     * Cancels the asynchronous task if it has not yet completed.
     */
    void cancel();

    /**
     * Returns whether the task has completed, either normally or with an exception.
     *
     * @return true if the task is done (success, failure, or canceled)
     */
    boolean isDone();

    /**
     * Returns whether the task completed successfully without exception.
     *
     * @return true if the task completed and no exception was thrown
     */
    boolean isSuccess();

    /**
     * Returns the result value if the task completed successfully.
     * Blocks until the task completes if it is still in progress.
     *
     * @return the result value of the successful invocation
     */
    Object getValue();

    /**
     * Returns the exception if the task completed with a failure or was canceled.
     *
     * @return the exception, or null if the task succeeded or has not yet completed
     */
    Throwable getThrowable();

    /**
     * Registers a listener to be notified when the task completes (success, failure,
     * timeout, or cancellation). If the task is already done, the listener is invoked
     * immediately.
     *
     * @param listener the listener to register
     */
    void addListener(FutureListener listener);
}