package org.hongxi.jaws.exception;

/**
 * Created by shenhongxi on 2020/7/26.
 */
public class JawsBizException extends JawsAbstractException {
    private static final long serialVersionUID = -9030222846555573201L;

    public JawsBizException() {
        super(JawsErrorMsgConstants.BIZ_DEFAULT_EXCEPTION);
    }

    public JawsBizException(JawsErrorMsg jawsErrorMsg) {
        super(jawsErrorMsg);
    }

    public JawsBizException(String message) {
        super(message, JawsErrorMsgConstants.BIZ_DEFAULT_EXCEPTION);
    }

    public JawsBizException(String message, JawsErrorMsg jawsErrorMsg) {
        super(message, jawsErrorMsg);
    }

    public JawsBizException(String message, Throwable cause) {
        super(message, cause, JawsErrorMsgConstants.BIZ_DEFAULT_EXCEPTION);
    }

    public JawsBizException(String message, Throwable cause, JawsErrorMsg jawsErrorMsg) {
        super(message, cause, jawsErrorMsg);
    }

    public JawsBizException(Throwable cause) {
        super(cause, JawsErrorMsgConstants.BIZ_DEFAULT_EXCEPTION);
    }

    public JawsBizException(Throwable cause, JawsErrorMsg jawsErrorMsg) {
        super(cause, jawsErrorMsg);
    }
}
