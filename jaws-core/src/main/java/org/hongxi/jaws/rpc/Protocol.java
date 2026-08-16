package org.hongxi.jaws.rpc;

import org.hongxi.jaws.common.extension.Scope;
import org.hongxi.jaws.common.extension.Spi;

/**
 * Created by shenhongxi on 2021/3/6.
 */
@Spi(scope = Scope.SINGLETON)
public interface Protocol {

    <T> Exporter<T> export(Provider<T> provider, URL url);

    <T> Reference<T> refer(Class<T> interfaceClass, URL url, URL serviceUrl);

    void destroy();
}