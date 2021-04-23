package org.hongxi.jaws.cluster.ha;

import org.hongxi.jaws.cluster.LoadBalance;
import org.hongxi.jaws.common.extension.SpiMeta;
import org.hongxi.jaws.rpc.Referer;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;

/**
 * 
 * Fail fast ha strategy.
 *
 * Created by shenhongxi on 2021/4/23.
 */
@SpiMeta(name = "failfast")
public class FailfastHaStrategy<T> extends AbstractHaStrategy<T> {

    @Override
    public Response call(Request request, LoadBalance<T> loadBalance) {
        Referer<T> refer = loadBalance.select(request);
        return refer.call(request);
    }
}
