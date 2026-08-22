package org.hongxi.jaws.exception;

import java.io.Serial;

/**
 * Business-level exception for provider-side business errors.
 */
public class JawsBizException extends JawsAbstractException {
    @Serial
    private static final long serialVersionUID = -9030222846555573201L;

    public JawsBizException() {
        super();
        this.errorCode = JawsErrorCode.BIZ_DEFAULT;
    }

    public JawsBizException(String message) {
        super(message);
        this.errorCode = JawsErrorCode.BIZ_DEFAULT;
    }

    public JawsBizException(String message, int errorCode) {
        super(message, errorCode);
    }

    public JawsBizException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = JawsErrorCode.BIZ_DEFAULT;
    }

    public JawsBizException(String message, Throwable cause, int errorCode) {
        super(message, cause, errorCode);
    }

    public JawsBizException(Throwable cause) {
        super(cause);
        this.errorCode = JawsErrorCode.BIZ_DEFAULT;
    }
}
