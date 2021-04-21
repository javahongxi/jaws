package org.hongxi.jaws.rpc;

import org.hongxi.jaws.common.extension.Scope;
import org.hongxi.jaws.common.extension.Spi;

/**
 * Created by shenhongxi on 2021/4/21.
 */
@Spi(scope = Scope.PROTOTYPE)
public interface Referer<T> extends Caller<T>, Node {

    /**
     * 当前使用该referer的调用数
     * 
     * @return
     */
    int activeRefererCount();

    /**
     * 获取referer的原始service url
     * 
     * @return
     */
    URL getServiceUrl();
}