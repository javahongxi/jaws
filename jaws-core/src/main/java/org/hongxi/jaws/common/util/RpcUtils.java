package org.hongxi.jaws.common.util;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.rpc.DefaultResponse;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.URL;

/**
 * Utility methods for RPC service key construction, error response building,
 * and request descriptor formatting.
 */
public final class RpcUtils {

    private RpcUtils() {
    }

    public static String getServiceKey(Request request) {
        String group = getGroupFromRequest(request);
        String version = getVersionFromRequest(request);
        return getServiceKey(group, request.getInterfaceName(), version);
    }

    public static String getServiceKey(URL url) {
        return getServiceKey(url.getGroup(), url.getPath(), url.getVersion());
    }

    private static String getServiceKey(String group, String interfaceName, String version) {
        return group + JawsConstants.PATH_SEPARATOR + interfaceName + JawsConstants.PATH_SEPARATOR + version;
    }

    public static String getGroupFromRequest(Request request) {
        return getValueFromRequest(request, UrlParam.Identity.GROUP.getName(), UrlParam.Identity.GROUP.value());
    }

    public static String getVersionFromRequest(Request request) {
        return getValueFromRequest(request, UrlParam.Identity.VERSION.getName(), UrlParam.Identity.VERSION.value());
    }

    public static String getValueFromRequest(Request request, String key, String defaultValue) {
        String value = defaultValue;
        if (request.getAttachments() != null && request.getAttachments().containsKey(key)) {
            value = request.getAttachments().get(key);
        }
        return value;
    }

    public static DefaultResponse buildErrorResponse(Request request, Exception e) {
        return buildErrorResponse(request.getRequestId(), e);
    }

    public static DefaultResponse buildErrorResponse(long requestId, Exception e) {
        DefaultResponse response = new DefaultResponse();
        response.setRequestId(requestId);
        response.setException(e);
        return response;
    }

    /**
     * protocol key: protocol://host:port/group/interface/version
     */
    public static String getProtocolKey(URL url) {
        StringBuilder key = new StringBuilder();
        key.append(url.getProtocol());
        key.append(JawsConstants.PROTOCOL_SEPARATOR);
        key.append(url.getHostPort());
        key.append(JawsConstants.PATH_SEPARATOR);
        key.append(url.getGroup());
        key.append(JawsConstants.PATH_SEPARATOR);
        key.append(url.getPath());
        key.append(JawsConstants.PATH_SEPARATOR);
        key.append(url.getVersion());
        return key.toString();
    }

    /**
     * Get the full method descriptor in the form of interface.method(paramDesc).
     * <p>
     * <pre>
     * 		For example:
     * 			package org.hongxi.jaws;
     *
     * 			interface A { public hello(int age); }
     *
     * 			Then return "org.hongxi.jaws.A.hell(int)"
     * </pre>
     */
    public static String getFullMethodString(Request request) {
        return request.getInterfaceName() + "." + request.getMethodName() + "(" + request.getParamDesc() + ")";
    }

    public static String toString(Request request) {
        return "requestId=" + request.getRequestId() +
                " interface=" + request.getInterfaceName() +
                " method=" + request.getMethodName()
                + "(" + request.getParamDesc() + ")";
    }
}
