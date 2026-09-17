package org.hongxi.jaws.wire;

/**
 * Handler for a client-side gRPC call, representing the next element in the
 * interceptor chain. The terminal implementation executes the actual gRPC call.
 * <p>
 * This is the client-side counterpart of grpc-java's {@code Channel}.
 *
 * @author shenhongxi
 * @see WireClientInterceptor
 */
public interface WireClientCallHandler {

    /**
     * Create the next {@link WireClientCall} in the chain. The returned call
     * is what the framework uses to actually send the request.
     *
     * @param call the client call facade for the current call
     * @return the next client call in the chain
     */
    WireClientCall newCall(WireClientCall call);
}
