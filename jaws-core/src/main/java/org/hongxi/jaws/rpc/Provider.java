package org.hongxi.jaws.rpc;

import org.hongxi.jaws.common.extension.Scope;
import org.hongxi.jaws.common.extension.Spi;

import java.lang.reflect.Method;

/**
 * Created by shenhongxi on 2021/3/6.
 */
@Spi(scope = Scope.PROTOTYPE)
public interface Provider<T> extends Caller<T> {

    Class<T> getInterface();

    Method lookupMethod(String methodName, String methodDesc);

    T getImpl();
}