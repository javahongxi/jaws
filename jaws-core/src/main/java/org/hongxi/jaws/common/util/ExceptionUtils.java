package org.hongxi.jaws.common.util;

import org.hongxi.jaws.exception.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Created by shenhongxi on 2020/7/26.
 */
public class ExceptionUtils {
    private static final Logger log = LoggerFactory.getLogger(ExceptionUtils.class);

    public static final StackTraceElement[] REMOTE_MOCK_STACK = new StackTraceElement[]{
            new StackTraceElement("remoteClass", "remoteMethod", "remoteFile", 1)};

    public static boolean isBizException(Throwable t) {
        return t instanceof JawsBizException;
    }

    public static boolean isJawsException(Throwable t) {
        return t instanceof JawsAbstractException;
    }

    /**
     * Replace the stack trace of the given exception with a mock one
     * to hide server-side stack details from the consumer.
     */
    public static void setMockStackTrace(Throwable e) {
        if (e != null) {
            try {
                e.setStackTrace(REMOTE_MOCK_STACK);
            } catch (Exception e1) {
                log.warn("replace remote exception stack failed! {}", e1.getMessage());
            }
        }
    }
}
