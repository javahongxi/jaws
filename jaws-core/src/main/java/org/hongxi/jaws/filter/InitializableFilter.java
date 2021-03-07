package org.hongxi.jaws.filter;

import org.hongxi.jaws.common.extension.Scope;
import org.hongxi.jaws.common.extension.Spi;
import org.hongxi.jaws.rpc.Caller;

/**
 * Created by shenhongxi on 2021/3/6.
 */
@Spi(scope = Scope.PROTOTYPE)
public interface InitializableFilter extends Filter {
    /**
     * init with caller eg. referer or provider be careful when using SINGLETON scope
     * 
     * @param caller
     */
    void init(Caller<?> caller);

}