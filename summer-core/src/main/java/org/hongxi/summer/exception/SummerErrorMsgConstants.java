package org.hongxi.summer.exception;

/**
 * Created by shenhongxi on 2020/6/26.
 */
public class SummerErrorMsgConstants {

    // framework error
    public static final int FRAMEWORK_DEFAULT_ERROR_CODE = 20001;

    public static final SummerErrorMsg FRAMEWORK_DEFAULT_ERROR = new SummerErrorMsg(503,
            FRAMEWORK_DEFAULT_ERROR_CODE, "framework default error");
}
