package org.hongxi.jaws.rpc;

/**
 * Created by shenhongxi on 2021/3/6.
 */
public interface Caller<T> extends Node {

    Class<T> getInterface();

    Response call(Request request);
}