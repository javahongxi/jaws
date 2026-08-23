package org.hongxi.jaws.filter;

import org.hongxi.jaws.common.extension.Spi;
import org.hongxi.jaws.rpc.Caller;

/**
 * SPI for filters that need per-caller initialization before use.
 * <p>
 * Unlike plain {@link Filter} extensions, {@code InitializableFilter} is
 * prototype-scoped: each reference or provider gets its own filter instance,
 * initialized with the owning {@link Caller} via {@link #init}.
 *
 * <p>
 * Created by shenhongxi on 2021/3/6.
 */
@Spi
public interface InitializableFilter extends Filter {
    /**
     * Initialize the filter with the associated caller (e.g. reference or provider).
     *
     * @param caller the caller that owns this filter instance
     */
    void init(Caller<?> caller);
}