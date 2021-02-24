package org.hongxi.jaws.common.util;

import com.alibaba.fastjson.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.hongxi.jaws.exception.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Created by shenhongxi on 2020/7/26.
 */
public class ExceptionUtils {
    private static final Logger logger = LoggerFactory.getLogger(ExceptionUtils.class);

    public static final StackTraceElement[] REMOTE_MOCK_STACK = new StackTraceElement[]{
            new StackTraceElement("remoteClass", "remoteMethod", "remoteFile", 1)};

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
    public static boolean isSummerException(Throwable t) {
        return t instanceof JawsAbstractException;
    }

    public static String toMessage(Exception e) {
        JSONObject jsonObject = new JSONObject();
        int type = 1;
        int code = 500;
        String errmsg = null;

        if (e instanceof JawsFrameworkException) {
            JawsFrameworkException sfe = (JawsFrameworkException) e;
            type = 0;
            code = sfe.getErrorCode();
            errmsg = sfe.getOriginMessage();
        } else if (e instanceof JawsServiceException) {
            JawsServiceException mse = (JawsServiceException) e;
            type = 1;
            code = mse.getErrorCode();
            errmsg = mse.getOriginMessage();
        } else if (e instanceof JawsBizException) {
            JawsBizException sbe = (JawsBizException) e;
            type = 2;
            code = sbe.getErrorCode();
            errmsg = sbe.getOriginMessage();
            if (sbe.getCause() != null) {
                errmsg = errmsg + ", cause:" + sbe.getCause().getMessage();
            }
        } else {
            errmsg = e.getMessage();
        }
        jsonObject.put("errcode", code);
        jsonObject.put("errmsg", errmsg);
        jsonObject.put("errtype", type);
        return jsonObject.toString();
    }

    public static JawsAbstractException fromMessage(String msg) {
        if (StringUtils.isNotBlank(msg)) {
            try {
                JSONObject jsonObject = JSONObject.parseObject(msg);
                int type = jsonObject.getIntValue("errtype");
                int errcode = jsonObject.getIntValue("errcode");
                String errmsg = jsonObject.getString("errmsg");
                JawsAbstractException e = null;
                switch (type) {
                    case 1:
                        e = new JawsServiceException(errmsg, new JawsErrorMsg(errcode, errcode, errmsg));
                        break;
                    case 2:
                        e = new JawsBizException(errmsg, new JawsErrorMsg(errcode, errcode, errmsg));
                        break;
                    default:
                        e = new JawsFrameworkException(errmsg, new JawsErrorMsg(errcode, errcode, errmsg));
                }
                return e;
            } catch (Exception e) {
                logger.warn("build exception from msg fail. msg:{}", msg);
            }
        }
        return null;
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
                logger.warn("replace remote exception stack fail! {}", e1.getMessage());
            }
        }
    }
}
