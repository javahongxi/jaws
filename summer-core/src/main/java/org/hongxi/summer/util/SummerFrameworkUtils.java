package org.hongxi.summer.util;

import org.hongxi.summer.common.SummerConstants;
import org.hongxi.summer.rpc.DefaultResponse;

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
}
