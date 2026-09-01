package org.hongxi.jaws.rpc;

import java.util.Map;

/**
 * Represents an RPC response returned from provider to consumer.
 * <p>
 * Carries the invocation result value (or exception), processing time,
 * and framework-level attachments corresponding to the originating {@link Request}.
 *
 * @author shenhongxi
 */
public interface Response {

    /**
     * Returns the invocation result value.
     * <p>
     * If the request was processed successfully, the return value is provided.
     * If an exception occurred during processing, this method throws it.
     *
     * @return result value
     * @throws RuntimeException if the remote invocation failed
     */
    Object getValue();

    /**
     * Returns the raw invocation result value without throwing, even if an
     * exception is set. Transport layers use this to serialize value and
     * exception independently.
     *
     * @return result value, or {@code null} if not set
     */
    Object getRawValue();

    /**
     * Returns the exception thrown during request processing, or {@code null} if
     * the request has completed normally or has not yet been processed.
     * <p>
     * This method is non-blocking regardless of whether the request has completed.
     *
     * @return exception instance, or {@code null}
     */
    Exception getException();

    /**
     * Returns the request id that this response corresponds to.
     *
     * @return request id
     */
    long getRequestId();

    /**
     * Returns the server-side business processing time in milliseconds.
     *
     * @return process time in milliseconds
     */
    long getProcessTime();

    /**
     * Returns the request timeout in milliseconds.
     *
     * @return timeout in milliseconds
     */
    int getTimeout();

    /**
     * Returns all framework-level attachments carried by this response.
     *
     * @return map of attachments
     */
    Map<String, String> getAttachments();

    /**
     * Sets a framework-level attachment on this response.
     *
     * @param key   attachment key
     * @param value attachment value
     */
    void setAttachment(String key, String value);

    /**
     * Returns the serialization protocol identifier used for encoding this response.
     *
     * @return serialization number
     */
    byte getSerializationNumber();
}
