package org.hongxi.jaws.cluster;

import org.hongxi.jaws.common.extension.Scope;
import org.hongxi.jaws.common.extension.Spi;
import org.hongxi.jaws.rpc.URL;

import java.util.List;

/**
 * Router spi for filtering provider urls before load balance.
 * <p>
 * Created by shenhongxi on 2025/7/19.
 */
@Spi(scope = Scope.SINGLETON)
public interface Router {

    /**
     * Filter provider urls based on consumer url conditions.
     *
     * @param urls        candidate provider urls
     * @param consumerUrl consumer url with routing conditions
     * @return filtered urls
     */
    List<URL> route(List<URL> urls, URL consumerUrl);
}
