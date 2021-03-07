package org.hongxi.jaws.config.handler;

import org.hongxi.jaws.common.extension.Scope;
import org.hongxi.jaws.common.extension.Spi;
import org.hongxi.jaws.rpc.Exporter;
import org.hongxi.jaws.rpc.URL;

import java.util.Collection;
import java.util.List;

/**
 * 
 * Handle urls which are from config.
 *
 * Created by shenhongxi on 2021/3/6.
 */
@Spi(scope = Scope.SINGLETON)
public interface ConfigHandler {

    <T> Exporter<T> export(Class<T> interfaceClass, T ref, List<URL> registryUrls, URL serviceUrl);

    <T> void unexport(List<Exporter<T>> exporters, Collection<URL> registryUrls);
}