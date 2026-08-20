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
     * Initialize the filter with the associated caller (e.g. reference or provider).
     *
     * @param caller the caller that owns this filter instance
     */
    void init(Caller<?> caller);
}