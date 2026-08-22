package org.hongxi.jaws.exception;

import java.io.Serial;

/**
 * Service-level exception for errors such as service not found, timeout, reject, etc.
 */
public class JawsServiceException extends JawsAbstractException {
    @Serial
    private static final long serialVersionUID = 167949946546769763L;

    public JawsServiceException() {
        super();
        this.errorCode = JawsErrorCode.SERVICE_DEFAULT;
    }

    public JawsServiceException(String message) {
        super(message);
        this.errorCode = JawsErrorCode.SERVICE_DEFAULT;
    }

    public JawsServiceException(String message, int errorCode) {
        super(message, errorCode);
    }

    public JawsServiceException(String message, int errorCode, boolean writableStackTrace) {
        super(message, errorCode, writableStackTrace);
    }

    public JawsServiceException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = JawsErrorCode.SERVICE_DEFAULT;
    }

    public JawsServiceException(String message, Throwable cause, int errorCode) {
        super(message, cause, errorCode);
    }

    public JawsServiceException(Throwable cause) {
        super(cause);
        this.errorCode = JawsErrorCode.SERVICE_DEFAULT;
    }
}
