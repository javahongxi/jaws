package org.hongxi.jaws.sample.wire.provider.service;

import org.hongxi.jaws.sample.wire.proto.CalculatorService;
import org.hongxi.jaws.sample.wire.proto.DivideReply;
import org.hongxi.jaws.sample.wire.proto.DivideRequest;
import org.hongxi.jaws.sample.wire.proto.FibonacciReply;
import org.hongxi.jaws.sample.wire.proto.FibonacciRequest;
import org.hongxi.jaws.transport.StreamSubject;
import org.hongxi.jaws.stream.StreamSource;

/**
 * Calculator service implementation for the wire sample — the second service
 * sharing the provider's port with {@link GreeterServiceImpl}.
 */
public class CalculatorServiceImpl implements CalculatorService {

    /** Refuse to divide by zero rather than invent a result. */
    private static final int MAX_FIBONACCI = 92;

    @Override
    public DivideReply divide(DivideRequest request) {
        long dividend = request.getDividend();
        long divisor = request.getDivisor();
        if (divisor == 0) {
            throw new IllegalArgumentException("divisor must not be zero");
        }
        System.out.println("Divide: " + dividend + " / " + divisor);
        return DivideReply.newBuilder()
                .setQuotient(dividend / divisor)
                .setRemainder(dividend % divisor)
                .build();
    }

    @Override
    public StreamSource<FibonacciReply> fibonacci(FibonacciRequest request) {
        int count = Math.min(request.getCount(), MAX_FIBONACCI);
        System.out.println("Fibonacci stream: count=" + request.getCount()
                + (count < request.getCount() ? " (capped at " + MAX_FIBONACCI + ")" : ""));
        StreamSubject<FibonacciReply> observer = new StreamSubject<>();
        // Emit from a background thread so the transport can subscribe before
        // the first item is produced, exactly like the greeter's stream.
        Thread thread = new Thread(() -> {
            long previous = 0;
            long current = 1;
            for (int i = 1; i <= count; i++) {
                observer.onNext(FibonacciReply.newBuilder()
                        .setIndex(i)
                        .setValue(previous)
                        .build());
                long next = previous + current;
                previous = current;
                current = next;
            }
            observer.onCompleted();
        });
        thread.setDaemon(true);
        thread.start();
        return observer;
    }
}
