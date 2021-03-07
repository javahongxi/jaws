package org.hongxi.jaws.registry;

import org.hongxi.jaws.rpc.URL;

import java.util.List;

/**
 * Notify when service changed.
 *
 * Created by shenhongxi on 2021/3/7.
 */

public interface NotifyListener {

    void notify(URL registryUrl, List<URL> urls);
}