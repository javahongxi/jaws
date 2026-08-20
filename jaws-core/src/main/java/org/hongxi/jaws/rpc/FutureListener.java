package org.hongxi.jaws.rpc;

/**
 * Listener for receiving completion notifications of an asynchronous {@link Future}.
 * <p>
 * Callbacks should be lightweight and non-blocking.
 *
 * @see Future#addListener(FutureListener)
 */
public interface FutureListener {

    /**
     * Invoked when the {@link Future} completes (success, exception, or cancellation).
     *
     * @param future the completed future
     * @throws Exception if an error occurs during notification handling
     */
    void operationComplete(Future future) throws Exception;
}
