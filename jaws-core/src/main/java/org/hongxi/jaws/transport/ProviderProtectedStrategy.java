package org.hongxi.jaws.transport;

import org.hongxi.jaws.common.extension.Scope;
import org.hongxi.jaws.common.extension.Spi;
import org.hongxi.jaws.rpc.Provider;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;

import java.util.concurrent.atomic.AtomicInteger;

@Spi(scope = Scope.PROTOTYPE)
public interface ProviderProtectedStrategy {

    Response call(Request request, Provider<?> provider);

    void setMethodCounter(AtomicInteger methodCounter);

}