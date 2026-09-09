package org.hongxi.jaws.exception;

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

    /**
     * {@inheritDoc}
     * <p>
     * No request id here on purpose. It used to be frozen from
     * {@code RpcContext} at construction time, which made its value depend on
     * which thread built the exception: null on an I/O or continuation thread,
     * and possibly the previous call's id on a pooled provider thread whose
     * context had not been cleared. The id belongs to the {@code Request} and
     * {@code Response} that carry it; a failure site that knows the id states it
     * in its own message instead.
     */
    @Override
    public String getMessage() {
        return String.format("error_message: %s, error_code: %d",
                getOriginMessage(), errorCode);
    }

    public String getOriginMessage() {
        return super.getMessage();
    }

    public int getErrorCode() {
        return errorCode;
    }
}
