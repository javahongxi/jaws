package org.hongxi.jaws.common.util;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
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
    public static boolean isJawsException(Throwable t) {
        return t instanceof JawsAbstractException;
    }

    public static String toMessage(Exception e) {
        JSONObject jsonObject = new JSONObject();
        int type = 1;
        int code = 500;
        String errmsg = null;

        if (e instanceof JawsFrameworkException jfe) {
            type = 0;
            code = jfe.getErrorCode();
            errmsg = jfe.getOriginMessage();
        } else if (e instanceof JawsServiceException jse) {
            type = 1;
            code = jse.getErrorCode();
            errmsg = jse.getOriginMessage();
        } else if (e instanceof JawsBizException jbe) {
            type = 2;
            code = jbe.getErrorCode();
            errmsg = jbe.getOriginMessage();
            if (jbe.getCause() != null) {
                errmsg = errmsg + ", cause:" + jbe.getCause().getMessage();
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
                JSONObject jsonObject = JSON.parseObject(msg);
                int type = jsonObject.getIntValue("errtype");
                int errcode = jsonObject.getIntValue("errcode");
                String errmsg = jsonObject.getString("errmsg");
                return switch (type) {
                    case 1 -> new JawsServiceException(errmsg, new JawsErrorMsg(errcode, errcode, errmsg));
                    case 2 -> new JawsBizException(errmsg, new JawsErrorMsg(errcode, errcode, errmsg));
                    default -> new JawsFrameworkException(errmsg, new JawsErrorMsg(errcode, errcode, errmsg));
                };
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
