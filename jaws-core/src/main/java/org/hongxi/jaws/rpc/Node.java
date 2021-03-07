package org.hongxi.jaws.rpc;

/**
 * Created by shenhongxi on 2021/3/6.
 */
public interface Node {

    void init();

    void destroy();

    boolean isAvailable();

    String desc();

    URL getUrl();
}