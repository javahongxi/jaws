package org.hongxi.jaws.exception;

import org.hongxi.jaws.rpc.RpcContext;

/**
 * Created by shenhongxi on 2020/6/26.
 */
public abstract class JawsAbstractException extends RuntimeException {
    private static final long serialVersionUID = -6842400415484759967L;

    protected JawsErrorMsg jawsErrorMsg = JawsErrorMsgConstants.FRAMEWORK_DEFAULT_ERROR;
    protected String errorMsg;

    public JawsAbstractException() {
        super();
    }

    public JawsAbstractException(JawsErrorMsg jawsErrorMsg) {
        super();
        this.jawsErrorMsg = jawsErrorMsg;
    }

    public JawsAbstractException(String message) {
        super(message);
        this.errorMsg = message;
    }

    public JawsAbstractException(String message, JawsErrorMsg jawsErrorMsg) {
        super(message);
        this.jawsErrorMsg = jawsErrorMsg;
        this.errorMsg = message;
    }

    public JawsAbstractException(String message, Throwable cause) {
        super(message, cause);
        this.errorMsg = message;
    }

    public JawsAbstractException(String message, Throwable cause, JawsErrorMsg jawsErrorMsg) {
        super(message, cause);
        this.jawsErrorMsg = jawsErrorMsg;
        this.errorMsg = message;
    }

    public JawsAbstractException(Throwable cause) {
        super(cause);
    }

    public JawsAbstractException(Throwable cause, JawsErrorMsg jawsErrorMsg) {
        super(cause);
        this.jawsErrorMsg = jawsErrorMsg;
    }

    @Override
    public String getMessage() {
        String message = getOriginMessage();
        return String.format("error_message: %s, status: %d, error_code: %d, request_id: %s",
                message, getStatus(), getErrorCode(), RpcContext.getContext().getRequestId());
    }

    public String getOriginMessage() {
        if (jawsErrorMsg == null) return super.getMessage();

        if (errorMsg != null && !errorMsg.equals("")) {
            return errorMsg;
        }
        return jawsErrorMsg.getMessage();
    }

    public int getStatus() {
        return jawsErrorMsg != null ? jawsErrorMsg.getStatus() : 0;
    }

    public int getErrorCode() {
        return jawsErrorMsg != null ? jawsErrorMsg.getErrorCode() : 0;
    }

    public JawsErrorMsg getJawsErrorMsg() {
        return jawsErrorMsg;
    }
}
