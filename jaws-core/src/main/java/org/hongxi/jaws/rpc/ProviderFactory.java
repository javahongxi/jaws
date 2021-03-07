package org.hongxi.jaws.rpc;

/**
 * Created by shenhongxi on 2021/3/7.
 */
public interface ProviderFactory {

    <T> Provider<T> newProvider(T proxyImpl, URL url, Class<T> clz);
}