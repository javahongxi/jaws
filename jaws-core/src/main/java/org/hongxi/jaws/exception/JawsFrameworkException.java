package org.hongxi.jaws.exception;

/**
 * Created by shenhongxi on 2020/6/26.
 */
public class JawsFrameworkException extends JawsAbstractException {
    private static final long serialVersionUID = -6860263607854518306L;

    public JawsFrameworkException() {
        super(JawsErrorMsgConstants.FRAMEWORK_DEFAULT_ERROR);
    }

    public JawsFrameworkException(JawsErrorMsg jawsErrorMsg) {
        super(jawsErrorMsg);
    }

    public JawsFrameworkException(String message) {
        super(message, JawsErrorMsgConstants.FRAMEWORK_DEFAULT_ERROR);
    }

    public JawsFrameworkException(String message, JawsErrorMsg jawsErrorMsg) {
        super(message, jawsErrorMsg);
    }

    public JawsFrameworkException(String message, Throwable cause) {
        super(message, cause, JawsErrorMsgConstants.FRAMEWORK_DEFAULT_ERROR);
    }

    public JawsFrameworkException(String message, Throwable cause, JawsErrorMsg jawsErrorMsg) {
        super(message, cause, jawsErrorMsg);
    }

    public JawsFrameworkException(Throwable cause) {
        super(cause, JawsErrorMsgConstants.FRAMEWORK_DEFAULT_ERROR);
    }

    public JawsFrameworkException(Throwable cause, JawsErrorMsg jawsErrorMsg) {
        super(cause, jawsErrorMsg);
    }
}
