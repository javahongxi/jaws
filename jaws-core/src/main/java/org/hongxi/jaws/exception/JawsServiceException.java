package org.hongxi.jaws.exception;

/**
 * Created by shenhongxi on 2020/7/25.
 */
public class JawsServiceException extends JawsAbstractException {
    private static final long serialVersionUID = 167949946546769763L;

    public JawsServiceException() {
        super(JawsErrorMsgConstants.SERVICE_DEFAULT_ERROR);
    }

    public JawsServiceException(JawsErrorMsg jawsErrorMsg) {
        super(jawsErrorMsg);
    }

    public JawsServiceException(String message) {
        super(message, JawsErrorMsgConstants.SERVICE_DEFAULT_ERROR);
    }

    public JawsServiceException(String message, JawsErrorMsg jawsErrorMsg) {
        super(message, jawsErrorMsg);
    }

    public JawsServiceException(String message, Throwable cause) {
        super(message, cause, JawsErrorMsgConstants.SERVICE_DEFAULT_ERROR);
    }

    public JawsServiceException(String message, Throwable cause, JawsErrorMsg jawsErrorMsg) {
        super(message, cause, jawsErrorMsg);
    }

    public JawsServiceException(Throwable cause) {
        super(cause, JawsErrorMsgConstants.SERVICE_DEFAULT_ERROR);
    }

    public JawsServiceException(Throwable cause, JawsErrorMsg jawsErrorMsg) {
        super(cause, jawsErrorMsg);
    }
}
