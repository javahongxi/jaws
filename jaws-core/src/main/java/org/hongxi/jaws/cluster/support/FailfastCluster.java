package org.hongxi.jaws.cluster.support;

import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.common.extension.Extension;
import org.hongxi.jaws.common.util.ExceptionUtils;
import org.hongxi.jaws.common.util.JawsFrameworkUtils;
import org.hongxi.jaws.exception.JawsErrorCode;
import org.hongxi.jaws.exception.JawsAbstractException;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.RpcContext;

/**
 * Failfast cluster: fails immediately on the first error without retrying.
 */
@Extension("failfast")
public class FailfastCluster<T> extends AbstractCluster<T> {

    @Override
    public Response call(Request request) {
        if (!available.get()) {
            throw new JawsServiceException("Cluster not available, interface=" + getInterface(),
                    JawsErrorCode.SERVICE_NOT_FOUND, false);
        }

        try {
            Reference<T> refer = loadBalance.select(request);
            RpcContext.getContext().setServerUrl(refer.getUrl());
            return refer.call(request);
        } catch (Exception e) {
            if (ExceptionUtils.isBizException(e)) {
                throw (RuntimeException) e;
            }
            if (!url.getBoolParameter(UrlParam.Client.THROW_EXCEPTION)) {
                return JawsFrameworkUtils.buildErrorResponse(request, e);
            }
            if (e instanceof JawsAbstractException jae) {
                throw jae;
            }
            throw new JawsServiceException("FailfastCluster call failed, request=" + request, e);
        }
    }
}
