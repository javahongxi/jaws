package org.hongxi.jaws.cluster;

import org.hongxi.jaws.common.extension.Scope;
import org.hongxi.jaws.common.extension.Spi;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.URL;

/**
 * Ha strategy.
 *
 * Created by shenhongxi on 2021/4/23.
 */
@Spi(scope = Scope.PROTOTYPE)
public interface HaStrategy<T> {

    void setUrl(URL url);

    Response call(Request request, LoadBalance<T> loadBalance);

}