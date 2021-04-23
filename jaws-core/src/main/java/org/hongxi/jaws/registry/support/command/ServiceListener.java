package org.hongxi.jaws.registry.support.command;

import org.hongxi.jaws.rpc.URL;

import java.util.List;

/**
 * Created by shenhongxi on 2021/4/23.
 */
public interface ServiceListener {

    void notifyService(URL refUrl, URL registryUrl, List<URL> urls);
}