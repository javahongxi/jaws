package org.hongxi.jaws.cluster;

import org.hongxi.jaws.common.extension.Scope;
import org.hongxi.jaws.common.extension.Spi;
import org.hongxi.jaws.rpc.Referer;
import org.hongxi.jaws.rpc.Request;

import java.util.List;

/**
 * Load balance for select referer
 *
 * Created by shenhongxi on 2021/4/23.
 */
@Spi(scope = Scope.PROTOTYPE)
public interface LoadBalance<T> {

    void onRefresh(List<Referer<T>> referers);

    Referer<T> select(Request request);

    void selectToHolder(Request request, List<Referer<T>> refersHolder);

    void setWeightString(String weightString);

}