package org.hongxi.jaws.rpc;

/**
 * Represents the result of an asynchronous RPC invocation.
 * <p>
 * Provides methods to check completion status, retrieve the result or exception,
 * and cancel the task.
 *
 * @see ResponseFuture
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
}
