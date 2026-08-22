package org.hongxi.jaws.exception;

/**
 * Centralized error code constants for the Jaws exception hierarchy.
 *
 * <p>Error code ranges:
 * <ul>
 *   <li>10xxx - Service errors (service not found, timeout, reject, etc.)</li>
 *   <li>20xxx - Framework errors (encode/decode, init, export, register, etc.)</li>
 *   <li>30xxx - Business errors (provider-side business exceptions)</li>
 * </ul>
 */
public final class JawsErrorCode {

    private JawsErrorCode() {
    }

    // ---- Service errors (10xxx) ----

    public static final int SERVICE_DEFAULT = 10001;
    public static final int SERVICE_REJECT = 10002;
    public static final int SERVICE_TIMEOUT = 10003;
    public static final int SERVICE_TASK_CANCEL = 10004;
    public static final int SERVICE_NOT_FOUND = 10101;
    public static final int SERVICE_METHOD_NOT_FOUND = 10102;
    public static final int SERVICE_REQUEST_LENGTH_OUT_OF_LIMIT = 10201;

    // ---- Framework errors (20xxx) ----

    public static final int FRAMEWORK_DEFAULT = 20001;
    public static final int FRAMEWORK_ENCODE = 20002;
    public static final int FRAMEWORK_DECODE = 20003;
    public static final int FRAMEWORK_INIT = 20004;
    public static final int FRAMEWORK_EXPORT = 20005;
    public static final int FRAMEWORK_REGISTER = 20008;

    // ---- Biz errors (30xxx) ----

    public static final int BIZ_DEFAULT = 30001;
}
