package org.hongxi.jaws.exception;

import org.hongxi.jaws.rpc.RpcContext;
import java.io.Serial;

/**
 * Base exception for all Jaws framework errors.
 *
 * <p>Subclasses categorize errors into three branches:
 * <ul>
 *   <li>{@link JawsFrameworkException} - framework-level errors (codec, init, registry)</li>
 *   <li>{@link JawsServiceException} - service-level errors (not found, timeout, reject)</li>
 *   <li>{@link JawsBizException} - business-level errors (provider-side exceptions)</li>
 * </ul>
 */
public abstract class JawsAbstractException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = -6842400415484759967L;

    protected int errorCode;

    /**
     * Request id captured at construction time. Exceptions travel across threads
     * and serialization boundaries, so the id must be frozen here instead of
     * reading the thread-local {@link RpcContext} lazily in {@link #getMessage()}.
     * Serializable, so the provider-side value survives transfer to the consumer.
     */
    private final String requestId = RpcContext.getContext().getRequestId();

    public JawsAbstractException() {
        super();
    }

    public JawsAbstractException(String message) {
        super(message);
    }

    public JawsAbstractException(String message, int errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public JawsAbstractException(Throwable cause) {
        super(cause);
    }

    public JawsAbstractException(String message, Throwable cause) {
        super(message, cause);
    }

    public JawsAbstractException(String message, Throwable cause, int errorCode) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public JawsAbstractException(String message, int errorCode, boolean writableStackTrace) {
        super(message, null, false, writableStackTrace);
        this.errorCode = errorCode;
    }

    @Override
    public String getMessage() {
        return String.format("error_message: %s, error_code: %d, request_id: %s",
                getOriginMessage(), errorCode, requestId);
    }

    public String getOriginMessage() {
        return super.getMessage();
    }

    public int getErrorCode() {
        return errorCode;
    }
}
