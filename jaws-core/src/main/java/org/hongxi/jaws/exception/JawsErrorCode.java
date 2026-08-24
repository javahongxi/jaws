package org.hongxi.jaws.exception;

/**
 * Centralized error code constants for the Jaws exception hierarchy.
 *
 * <p>Error code ranges:
 * <ul>
 *   <li>40xxx - Service errors (service not found, timeout, reject, etc.)</li>
 *   <li>50xxx - Framework errors (default, register, etc.)</li>
 *   <li>60xxx - Business errors (provider-side business exceptions)</li>
 * </ul>
 */
public final class JawsErrorCode {

    private JawsErrorCode() {
    }

    // ---- Service errors (40xxx) ----

    public static final int SERVICE_DEFAULT = 40001;
    public static final int SERVICE_REJECT = 40002;
    public static final int SERVICE_TIMEOUT = 40003;
    public static final int SERVICE_NOT_FOUND = 40101;
    public static final int SERVICE_METHOD_NOT_FOUND = 40102;

    // ---- Framework errors (50xxx) ----

    public static final int FRAMEWORK_DEFAULT = 50001;
    public static final int FRAMEWORK_REGISTER = 50008;

    // ---- Biz errors (60xxx) ----

    public static final int BIZ_DEFAULT = 60001;
}
