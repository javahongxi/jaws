package org.hongxi.summer.exception;

/**
 * Created by shenhongxi on 2020/6/26.
 */
public class SummerErrorMsgConstants {

    // service error status 503
    public static final int SERVICE_DEFAULT_ERROR_CODE = 10001;

    // service error start

    public static final SummerErrorMsg SERVICE_DEFAULT_ERROR =
            new SummerErrorMsg(503, SERVICE_DEFAULT_ERROR_CODE, "service error");

    // framework error
    public static final int FRAMEWORK_DEFAULT_ERROR_CODE = 20001;
    public static final int FRAMEWORK_ENCODE_ERROR_CODE = 20002;
    public static final int FRAMEWORK_DECODE_ERROR_CODE = 20003;
    // biz error
    public static final int BIZ_DEFAULT_ERROR_CODE = 30001;

    public static final SummerErrorMsg FRAMEWORK_DEFAULT_ERROR =
            new SummerErrorMsg(503, FRAMEWORK_DEFAULT_ERROR_CODE, "framework default error");

    public static final SummerErrorMsg FRAMEWORK_ENCODE_ERROR =
            new SummerErrorMsg(503, FRAMEWORK_ENCODE_ERROR_CODE, "framework encode error");
    public static final SummerErrorMsg FRAMEWORK_DECODE_ERROR =
            new SummerErrorMsg(503, FRAMEWORK_DECODE_ERROR_CODE, "framework decode error");

    public static final SummerErrorMsg BIZ_DEFAULT_EXCEPTION =
            new SummerErrorMsg(503, BIZ_DEFAULT_ERROR_CODE, "provider error");
}
