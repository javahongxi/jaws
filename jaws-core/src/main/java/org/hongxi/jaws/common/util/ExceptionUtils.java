package org.hongxi.jaws.common.util;

import org.hongxi.jaws.exception.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Created by shenhongxi on 2020/7/26.
 */
public class ExceptionUtils {
    public static final StackTraceElement[] REMOTE_MOCK_STACK = new StackTraceElement[]{
            new StackTraceElement("remoteClass", "remoteMethod", "remoteFile", 1)};
    private static final Logger log = LoggerFactory.getLogger(ExceptionUtils.class);

    /**
     * 判定是否是业务方的逻辑抛出的异常
     * <p>
     * <pre>
     * 		true: 来自业务方的异常
     * 		false: 来自框架本身的异常
     * </pre>
     *
     * @param t
     * @return
     */
    public static boolean isBizException(Throwable t) {
        return t instanceof JawsBizException;
    }


    /**
     * 是否框架包装过的异常
     *
     * @param t
     * @return
     */
    public static boolean isJawsException(Throwable t) {
        return t instanceof JawsAbstractException;
    }

    /**
     * 覆盖给定exception的stack信息，server端产生业务异常时调用此类屏蔽掉server端的异常栈。
     *
     * @param e
     */
    public static void setMockStackTrace(Throwable e) {
        if (e != null) {
            try {
                e.setStackTrace(REMOTE_MOCK_STACK);
            } catch (Exception e1) {
                log.warn("replace remote exception stack fail! {}", e1.getMessage());
            }
        }
    }
}
