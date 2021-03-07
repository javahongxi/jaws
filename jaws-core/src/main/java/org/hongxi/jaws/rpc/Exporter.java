package org.hongxi.jaws.rpc;

/**
 * Created by shenhongxi on 2021/3/6.
 */
public interface Exporter<T> extends Node {

    Provider<T> getProvider();

    void unexport();
}