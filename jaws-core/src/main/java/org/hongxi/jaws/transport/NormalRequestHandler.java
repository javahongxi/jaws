package org.hongxi.jaws.transport;

import org.hongxi.jaws.rpc.DefaultResponse;
import org.hongxi.jaws.rpc.Provider;
import org.hongxi.jaws.rpc.Request;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

/**
 * Handler for normal (non-generic) RPC invocations.
 * <p>
 * Directly invokes the provider method and sets the serialization number on the response.
 */
public class NormalRequestHandler extends AbstractRequestHandler {

    @Override
    protected CompletableFuture<Object> doHandleAsync(Request request, Provider<?> provider, Method method) {
        return callAsync(request, provider).thenApply(response -> {
            ((DefaultResponse) response).setSerializationNumber(request.getSerializationNumber());
            return response;
        });
    }
}
