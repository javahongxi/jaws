package org.hongxi.jaws.sample.wire.proto;

import org.hongxi.jaws.stream.StreamSource;

/**
 * Service interface for the Calculator service.
 * <p>
 * Exported alongside {@link GreeterService} on the same wire port, so the
 * second service of a shared server has a third-party-visible shape: its
 * method names are deliberately unlike Greeter's, which is what the
 * per-port protobuf lookup in {@code WireMessageHandler} has to resolve.
 */
public interface CalculatorService {

    DivideReply divide(DivideRequest request);

    /**
     * Server-streaming: emits the first {@code count} Fibonacci numbers.
     */
    StreamSource<FibonacciReply> fibonacci(FibonacciRequest request);
}
