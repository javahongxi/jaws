package org.hongxi.jaws.filter;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.common.extension.Extension;
import org.hongxi.jaws.rpc.Caller;
import org.hongxi.jaws.rpc.Provider;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Access log filter that records the execution status of each call.
 * Should be placed at the outermost layer to execute last.
 * Note: this filter has a performance impact; consider disabling it under high request volume.
 */
@Extension(value = "access", order = 100)
public class AccessLogFilter implements Filter {

    private static final Logger accessLog = LoggerFactory.getLogger("accessLog");

    private static final String ACCESS_LOG_SEPARATOR = "|";

    @Override
    public Response filter(Caller<?> caller, Request request) {
        boolean needLog = caller.getUrl().getBoolParameter(UrlParam.Server.ACCESS_LOG);
        if (needLog) {
            long t1 = System.currentTimeMillis();
            boolean success = false;
            try {
                Response response = caller.call(request);
                success = true;
                return response;
            } finally {
                long elapsed = System.currentTimeMillis() - t1;
                logAccess(caller, request, elapsed, success);
            }
        } else {
            return caller.call(request);
        }
    }

    private void logAccess(Caller<?> caller, Request request, long elapsed, boolean success) {
        boolean isProvider = caller instanceof Provider;

        StringBuilder builder = new StringBuilder(128);
        append(builder, isProvider ? JawsConstants.ENDPOINT_TYPE_SERVICE : JawsConstants.ENDPOINT_TYPE_REFERENCE);
        // For reference side, remote ip, application, module are obtained from caller URL;
        // for service side, they come from request attachments.
        if (isProvider) {
            append(builder, request.getAttachments().get(UrlParam.Server.HOST.getName()));
            append(builder, request.getAttachments().get(UrlParam.Identity.APPLICATION.getName()));
            append(builder, request.getAttachments().get(UrlParam.Identity.MODULE.getName()));
        } else {
            append(builder, caller.getUrl().getHost());
            append(builder, caller.getUrl().getParameter(UrlParam.Identity.APPLICATION.getName()));
            append(builder, caller.getUrl().getParameter(UrlParam.Identity.MODULE.getName()));
        }
        append(builder, request.getInterfaceName());
        append(builder, request.getMethodName());
        append(builder, request.getParamDesc());
        append(builder, success);
        append(builder, elapsed);

        accessLog.info(builder.substring(0, builder.length() - 1));
    }

    private void append(StringBuilder builder, Object field) {
        if (field != null) {
            builder.append(URLEncoder.encode(field.toString(), StandardCharsets.UTF_8));
        }
        builder.append(ACCESS_LOG_SEPARATOR);
    }
}