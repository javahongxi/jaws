package org.hongxi.jaws.rpc;

import java.lang.reflect.Method;
import java.util.concurrent.Flow;

/**
 * Server-side invocation abstraction wrapping a service implementation (roughly the
 * provider-side {@code Invoker} in Dubbo). Resolves request method signatures via
 * {@link #lookupMethod(String, String)} and exposes the backing implementation through
 * {@link #getImpl()}.
 * Instances are stateful and bound to one service URL, so they are created
 * per URL by the protocol rather than loaded as an SPI extension.
 *
 * <p>Created by shenhongxi on 2021/3/6.
 */
public interface Provider<T> extends Caller<T> {

    Method lookupMethod(String methodName, String paramDesc);

    T getImpl();

    /**
     * Unified streaming invocation that returns a Publisher of response items.
     * <p>
     * Handles all streaming modes:
     * <ul>
     *   <li>{@code requestStream == null} → server-streaming (the service method
     *       returns a {@link Flow.Publisher})</li>
     *   <li>{@code requestStream != null} → client-streaming or
     *       bidirectional-streaming (the stream is passed as the first argument
     *       to the service method)</li>
     * </ul>
     * The default implementation throws {@link UnsupportedOperationException} —
     * implementations must override this method to support streaming.
     *
     * @param request       the RPC request
     * @param requestStream a publisher emitting client request items, or
     *                      {@code null} for server-streaming
     * @return a Publisher emitting response items
     * @throws UnsupportedOperationException if streaming is not supported
     */
    @Override
    default Flow.Publisher<Object> callStream(Request request, Flow.Publisher<Object> requestStream) {
        throw new UnsupportedOperationException(
                "Streaming not supported by this provider. Override callStream() to enable.");
    }
}