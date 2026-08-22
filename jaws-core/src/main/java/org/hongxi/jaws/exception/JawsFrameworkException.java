package org.hongxi.jaws.exception;

import java.io.Serial;

/**
 * Framework-level exception for errors such as codec failure, initialization error,
 * registry error, etc.
 */
public class JawsFrameworkException extends JawsAbstractException {
    @Serial
    private static final long serialVersionUID = -6860263607854518306L;

    public JawsFrameworkException() {
        super();
        this.errorCode = JawsErrorCode.FRAMEWORK_DEFAULT;
    }

    public JawsFrameworkException(String message) {
        super(message);
        this.errorCode = JawsErrorCode.FRAMEWORK_DEFAULT;
    }

    public JawsFrameworkException(String message, int errorCode) {
        super(message, errorCode);
    }

    public JawsFrameworkException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = JawsErrorCode.FRAMEWORK_DEFAULT;
    }

    public JawsFrameworkException(String message, Throwable cause, int errorCode) {
        super(message, cause, errorCode);
    }

    public JawsFrameworkException(Throwable cause) {
        super(cause);
        this.errorCode = JawsErrorCode.FRAMEWORK_DEFAULT;
    }
}
