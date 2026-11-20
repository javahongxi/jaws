package org.hongxi.jaws.harbor.model;

/**
 * Base class for all Nacos-compatible response objects.
 * <p>
 * Every response carries {@code resultCode} and {@code success}
 * so that nacos-client can determine whether the call succeeded; a refusal
 * additionally carries {@code message}, the human-readable reason the server
 * put in its error body (the Nacos {@code ErrorResponse} shape). The
 * {@code errorCode} slot mirrors the nacos base response: typed failures
 * (like a config query's {@code CONFIG_NOT_FOUND}) are read from there, not
 * from {@code resultCode}.
 *
 * @author shenhongxi
 */
public class Response {

    private int resultCode;
    private boolean success;
    private int errorCode;
    private String message;

    public Response() {
    }

    public Response(int resultCode, boolean success) {
        this.resultCode = resultCode;
        this.success = success;
    }

    public static Response ok() {
        return new Response(200, true);
    }

    public static Response error(int code) {
        return new Response(code, false);
    }

    public int getResultCode() {
        return resultCode;
    }

    public void setResultCode(int resultCode) {
        this.resultCode = resultCode;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public int getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(int errorCode) {
        this.errorCode = errorCode;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
