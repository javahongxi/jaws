package org.hongxi.jaws.rpc;

import org.hongxi.jaws.common.extension.Scope;
import org.hongxi.jaws.common.extension.Spi;

/**
 * Created by shenhongxi on 2021/4/21.
 */
@Spi(scope = Scope.PROTOTYPE)
public interface Reference<T> extends Caller<T>, Node {

    /**
     * 当前使用该reference的调用数
     *
     * @return
     */
    int activeReferenceCount();

    /**
     * 获取reference的原始service url
     *
     * @return
     */
    URL getServiceUrl();
}