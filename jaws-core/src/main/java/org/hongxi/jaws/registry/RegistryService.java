package org.hongxi.jaws.registry;

import org.hongxi.jaws.rpc.URL;

/**
 * Created by shenhongxi on 2021/3/5.
 */
public interface RegistryService {

    void register(URL url);

    void unregister(URL url);
}