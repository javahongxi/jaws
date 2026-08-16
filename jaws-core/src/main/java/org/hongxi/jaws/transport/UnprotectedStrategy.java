package org.hongxi.jaws.transport;

import org.hongxi.jaws.common.extension.SpiMeta;
import org.hongxi.jaws.rpc.Provider;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by shenhongxi on 2021/4/22.
 */
@SpiMeta(name = "none")
public class UnprotectedStrategy implements ProviderProtectedStrategy {

    public UnprotectedStrategy() {
    }

    @Override
    public CompletableFuture<Response> call(Request request, Provider<?> provider) {
        return provider.callAsync(request);
    }

    @Override
    public void setMethodCounter(AtomicInteger methodCounter) {
    }
}