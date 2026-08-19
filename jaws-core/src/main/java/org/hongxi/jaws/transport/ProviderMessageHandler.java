package org.hongxi.jaws.transport;

import org.hongxi.jaws.rpc.Provider;
import org.hongxi.jaws.rpc.Request;

import java.util.concurrent.CompletableFuture;

/**
 * Composite message handler that dispatches requests to the appropriate
 * handler based on invocation type (normal vs generic).
 * <p>
 * Manages provider registration and delegates request processing to
 * {@link NormalRequestHandler} or {@link GenericRequestHandler}.
 */
public class ProviderMessageHandler implements MessageHandler {

    private final AbstractRequestHandler normalHandler = new NormalRequestHandler();
    private final AbstractRequestHandler genericHandler = new GenericRequestHandler();

    @Override
    public CompletableFuture<Object> handleAsync(Channel channel, Object message) {
        if (message instanceof Request request) {
            boolean isGeneric = "true".equals(request.getAttachments().get("$generic"));
            if (isGeneric) {
                return genericHandler.handleAsync(channel, message);
            }
        }
        return normalHandler.handleAsync(channel, message);
    }

    public void addProvider(Provider<?> provider) {
        normalHandler.addProvider(provider);
        genericHandler.addProvider(provider);
    }

    public void removeProvider(Provider<?> provider) {
        normalHandler.removeProvider(provider);
        genericHandler.removeProvider(provider);
    }
}
