package org.hongxi.jaws.rpc;

import org.hongxi.jaws.common.extension.Scope;
import org.hongxi.jaws.common.extension.Spi;

/**
 * protocol
 *
 * <pre>
 * 只负责点到点的通讯
 * </pre>
 * <p>
 * Created by shenhongxi on 2021/3/6.
 */
@Spi(scope = Scope.SINGLETON)
public interface Protocol {
    /**
     * 暴露服务
     *
     * @param <T>
     * @param provider
     * @param url
     * @return
     */
    <T> Exporter<T> export(Provider<T> provider, URL url);

    /**
     * 引用服务
     *
     * @param <T>
     * @param clazz
     * @param url
     * @param serviceUrl
     * @return
     */
    <T> Referer<T> refer(Class<T> clazz, URL url, URL serviceUrl);

    /**
     * <pre>
     * 		1） exporter destroy
     * 		2） referer destroy
     * </pre>
     *
     */
    void destroy();
}