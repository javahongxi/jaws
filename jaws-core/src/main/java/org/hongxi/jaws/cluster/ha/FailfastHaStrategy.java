package org.hongxi.jaws.cluster.ha;

import org.hongxi.jaws.cluster.LoadBalance;
import org.hongxi.jaws.common.extension.SpiMeta;
import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.RpcContext;

/**
 *
 * Fail fast ha strategy.
 * <p>
 * Created by shenhongxi on 2021/4/23.
 */
@SpiMeta(name = "failfast")
public class FailfastHaStrategy<T> extends AbstractHaStrategy<T> {

    @Override
    public Response call(Request request, LoadBalance<T> loadBalance) {
        Reference<T> refer = loadBalance.select(request);
        RpcContext.getContext().setServerUrl(refer.getUrl());
        return refer.call(request);
    }
}
