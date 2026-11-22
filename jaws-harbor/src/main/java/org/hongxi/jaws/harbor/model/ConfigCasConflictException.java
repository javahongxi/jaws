package org.hongxi.jaws.harbor.model;

/**
 * A compare-and-swap publish that lost: the {@code casMd5} the write carried
 * no longer matched the stored md5 when the entry was applied. The message is
 * the exact nacos refusal text ({@code ConfigOperationService}'s CAS failure)
 * because clients recognise the conflict by that string — nacos gives it no
 * dedicated errorCode, only {@code FAIL(500)} plus this message.
 * <p>
 * Lives in the contract layer, not the server's raft internals: the server
 * raises it at apply time and the native client raises the same type when it
 * reads the refusal off the wire, so a caller catches one exception either way.
 * Deterministic, not retryable: the same write against the same state loses
 * again, so the write path surfaces it instead of relaying or re-proposing.
 *
 * @author shenhongxi
 */
public class ConfigCasConflictException extends RuntimeException {

    public static final String MESSAGE = "Cas publish fail, server md5 may have changed.";

    public ConfigCasConflictException(String message) {
        super(message);
    }
}