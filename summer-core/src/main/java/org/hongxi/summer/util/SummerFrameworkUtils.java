package org.hongxi.summer.util;

import org.hongxi.summer.common.SummerConstants;
import org.hongxi.summer.rpc.DefaultResponse;
import org.hongxi.summer.rpc.Request;

/**
 * Created by shenhongxi on 2020/7/25.
 */
public class SummerFrameworkUtils {

    public static String removeAsyncSuffix(String path) {
        if (path != null && path.endsWith(SummerConstants.ASYNC_SUFFIX)) {
            return path.substring(0, path.length() - SummerConstants.ASYNC_SUFFIX.length());
        }
        return path;
    }

    public static DefaultResponse buildErrorResponse(long requestId, Exception e) {
        DefaultResponse response = new DefaultResponse();
        response.setRequestId(requestId);
        response.setException(e);
        return response;
    }

    /**
     * 输出请求的关键信息： requestId=** interface=** method=**(**)
     *
     * @param request
     * @return
     */
    public static String toString(Request request) {
        return "requestId=" + request.getRequestId() +
                " interface=" + request.getInterfaceName() +
                " method=" + request.getMethodName()
                + "(" + request.getParametersDesc() + ")";
    }
}
