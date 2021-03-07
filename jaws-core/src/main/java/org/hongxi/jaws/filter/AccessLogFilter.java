package org.hongxi.jaws.filter;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.common.extension.Activation;
import org.hongxi.jaws.common.extension.SpiMeta;
import org.hongxi.jaws.common.util.NetUtils;
import org.hongxi.jaws.common.util.StringTools;
import org.hongxi.jaws.rpc.Caller;
import org.hongxi.jaws.rpc.Provider;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <pre>
 * Access log filter
 *
 * 统计整个call的执行状况，尽量到最上层，最后执行.
 * 此filter会对性能产生一定影响，请求量较大时建议关闭。
 *
 * </pre>
 * 
 * @author fishermen
 * @version V1.0 created at: 2013-5-22
 */
@SpiMeta(name = "access")
@Activation(sequence = 100)
public class AccessLogFilter implements Filter {

    private static final Logger accessLog = LoggerFactory.getLogger("accessLog");

    private String side;

    @Override
    public Response filter(Caller<?> caller, Request request) {
        boolean needLog = caller.getUrl().getBooleanParameter(
                URLParamType.accessLog.getName(), URLParamType.accessLog.boolValue());
        if (needLog) {
            long t1 = System.currentTimeMillis();
            boolean success = false;
            try {
                Response response = caller.call(request);
                success = true;
                return response;
            } finally {
                long consumeTime = System.currentTimeMillis() - t1;
                logAccess(caller, request, consumeTime, success);
            }
        } else {
            return caller.call(request);
        }
    }

    private void logAccess(Caller<?> caller, Request request, long consumeTime, boolean success) {
        if (getSide() == null) {
            String side = caller instanceof Provider ? JawsConstants.NODE_TYPE_SERVICE : JawsConstants.NODE_TYPE_REFERER;
            setSide(side);
        }

        StringBuilder builder = new StringBuilder(128);
        append(builder, side);
        append(builder, caller.getUrl().getParameter(URLParamType.application.getName()));
        append(builder, caller.getUrl().getParameter(URLParamType.module.getName()));
        append(builder, NetUtils.getLocalAddress().getHostAddress());
        append(builder, request.getInterfaceName());
        append(builder, request.getMethodName());
        append(builder, request.getParametersDesc());
        // 对于client，url中的remote ip, application, module,referer 和 service获取的地方不同
        if (JawsConstants.NODE_TYPE_REFERER.equals(side)) {
            append(builder, caller.getUrl().getHost());
            append(builder, caller.getUrl().getParameter(URLParamType.application.getName()));
            append(builder, caller.getUrl().getParameter(URLParamType.module.getName()));
        } else {
            append(builder, request.getAttachments().get(URLParamType.host.getName()));
            append(builder, request.getAttachments().get(URLParamType.application.getName()));
            append(builder, request.getAttachments().get(URLParamType.module.getName()));
        }

        append(builder, success);
        append(builder, request.getAttachments().get(URLParamType.requestIdFromClient.getName()));
        append(builder, consumeTime);

        accessLog.info(builder.substring(0, builder.length() - 1));
    }

    private void append(StringBuilder builder, Object field) {
        if (field != null) {
            builder.append(StringTools.urlEncode(field.toString()));
        }
        builder.append(JawsConstants.ACCESS_LOG_SEPARATOR);
    }

    public String getSide() {
        return side;
    }

    public void setSide(String side) {
        this.side = side;
    }
}