package org.hongxi.jaws.registry;

import org.hongxi.jaws.rpc.URL;

import java.util.List;

/**
 * Discovery service.
 *
 * Created by shenhongxi on 2021/3/7.
 */

public interface DiscoveryService {

    void subscribe(URL url, NotifyListener listener);

    void unsubscribe(URL url, NotifyListener listener);

    List<URL> discover(URL url);
}