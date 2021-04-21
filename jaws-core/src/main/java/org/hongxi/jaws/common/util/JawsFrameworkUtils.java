package org.hongxi.jaws.common.util;

import org.apache.commons.lang3.StringUtils;
import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.rpc.DefaultResponse;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.URL;

/**
 * Created by shenhongxi on 2020/7/25.
 */
public class JawsFrameworkUtils {

    /**
     * 目前根据 group/interface/version 来唯一标示一个服务
     *
     * @param request
     * @return
     */

    public static String getServiceKey(Request request) {
        String version = getVersionFromRequest(request);
        String group = getGroupFromRequest(request);

        return getServiceKey(group, request.getInterfaceName(), version);
    }

    /**
     * 目前根据 group/interface/version 来唯一标示一个服务
     *
     * @param url
     * @return
     */
    public static String getServiceKey(URL url) {
        return getServiceKey(url.getGroup(), url.getPath(), url.getVersion());
    }

    /**
     * serviceKey: group/interface/version
     *
     * @param group
     * @param interfaceName
     * @param version
     * @return
     */
    private static String getServiceKey(String group, String interfaceName, String version) {
        return group + JawsConstants.PATH_SEPARATOR + interfaceName + JawsConstants.PATH_SEPARATOR + version;
    }

    public static String getGroupFromRequest(Request request) {
        return getValueFromRequest(request, URLParamType.group.name(), URLParamType.group.value());
    }

    public static String getVersionFromRequest(Request request) {
        return getValueFromRequest(request, URLParamType.version.name(), URLParamType.version.value());
    }

    public static String getValueFromRequest(Request request, String key, String defaultValue) {
        String value = defaultValue;
        if (request.getAttachments() != null && request.getAttachments().containsKey(key)) {
            value = request.getAttachments().get(key);
        }
        return value;
    }

    public static String removeAsyncSuffix(String path) {
        if (path != null && path.endsWith(JawsConstants.ASYNC_SUFFIX)) {
            return path.substring(0, path.length() - JawsConstants.ASYNC_SUFFIX.length());
        }
        return path;
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
     *
     * @param url
     * @return
     */
    public static String getProtocolKey(URL url) {
        StringBuilder key = new StringBuilder();
        key.append(url.getProtocol());
        key.append(JawsConstants.PROTOCOL_SEPARATOR);
        key.append(url.getServerPortStr());
        key.append(JawsConstants.PATH_SEPARATOR);
        key.append(url.getGroup());
        key.append(JawsConstants.PATH_SEPARATOR);
        key.append(url.getPath());
        key.append(JawsConstants.PATH_SEPARATOR);
        key.append(url.getVersion());
        return key.toString();
    }

    /**
     * 判断url:source和url:target是否可以使用共享的service channel(port) 对外提供服务
     * <p>
     * <pre>
     * 		1） protocol
     * 		2） codec
     * 		3） serialize
     * 		4） maxContentLength
     * 		5） maxServerConnection
     * 		6） maxWorkerThread
     * 		7） workerQueueSize
     * 		8） heartbeatFactory
     * </pre>
     *
     * @param source
     * @param target
     * @return
     */
    public static boolean checkIfCanShareServiceChannel(URL source, URL target) {
        if (!StringUtils.equals(source.getProtocol(), target.getProtocol())) {
            return false;
        }

        if (!StringUtils.equals(source.getParameter(URLParamType.codec.getName()),
                target.getParameter(URLParamType.codec.getName()))) {
            return false;
        }

        if (!StringUtils.equals(source.getParameter(URLParamType.serialization.getName()),
                target.getParameter(URLParamType.serialization.getName()))) {
            return false;
        }

        if (!StringUtils.equals(source.getParameter(URLParamType.maxContentLength.getName()),
                target.getParameter(URLParamType.maxContentLength.getName()))) {
            return false;
        }

        if (!StringUtils.equals(source.getParameter(URLParamType.maxServerConnections.getName()),
                target.getParameter(URLParamType.maxServerConnections.getName()))) {
            return false;
        }

        if (!StringUtils.equals(source.getParameter(URLParamType.maxWorkerThreads.getName()),
                target.getParameter(URLParamType.maxWorkerThreads.getName()))) {
            return false;
        }

        if (!StringUtils.equals(source.getParameter(URLParamType.workerQueueSize.getName()),
                target.getParameter(URLParamType.workerQueueSize.getName()))) {
            return false;
        }

        return StringUtils.equals(source.getParameter(URLParamType.heartbeatFactory.getName()),
                target.getParameter(URLParamType.heartbeatFactory.getName()));

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
