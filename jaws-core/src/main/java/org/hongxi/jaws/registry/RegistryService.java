package org.hongxi.jaws.registry;

import org.hongxi.jaws.rpc.URL;

import java.util.Collection;

/**
 * Created by shenhongxi on 2021/3/5.
 */
public interface RegistryService {

    /**
     * register service to registry
     *
     * @param url
     */
    void register(URL url);

    /**
     * unregister service to registry
     *
     * @param url
     */
    void unregister(URL url);

    Collection<URL> getRegisteredServiceUrls();
}