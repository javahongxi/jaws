package org.hongxi.jaws.sample.wire.boot.provider.service;

import org.hongxi.jaws.sample.wire.proto.CalculatorService;
import org.hongxi.jaws.sample.wire.proto.DivideReply;
import org.hongxi.jaws.sample.wire.proto.DivideRequest;
import org.hongxi.jaws.sample.wire.proto.FibonacciReply;
import org.hongxi.jaws.sample.wire.proto.FibonacciRequest;
import org.hongxi.jaws.spring.boot.annotation.JawsService;
import org.hongxi.jaws.stream.StreamSource;
import org.hongxi.jaws.transport.StreamSubject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Calculator service exported on the <b>same</b> wire port as
 * {@link GreeterServiceImpl} — one server per {@code host:port} serves both,
 * which is what grpcurl observes from the outside.
 */
@JawsService
public class CalculatorServiceImpl implements CalculatorService {
    private static final Logger log = LoggerFactory.getLogger(CalculatorServiceImpl.class);

    /** Refuse to divide by zero rather than invent a result. */
    private static final int MAX_FIBONACCI = 92;

    @Override
    public DivideReply divide(DivideRequest request) {
        long dividend = request.getDividend();
        long divisor = request.getDivisor();
        if (divisor == 0) {
            throw new IllegalArgumentException("divisor must not be zero");
        }
        log.info("Divide: {} / {}", dividend, divisor);
        return DivideReply.newBuilder()
                .setQuotient(dividend / divisor)
                .setRemainder(dividend % divisor)
                .build();
    }

    @Override
    public StreamSource<FibonacciReply> fibonacci(FibonacciRequest request) {
        int count = Math.min(request.getCount(), MAX_FIBONACCI);
        log.info("Fibonacci stream: count={}{}", request.getCount(),
                count < request.getCount() ? " (capped at " + MAX_FIBONACCI + ")" : "");
        StreamSubject<FibonacciReply> observer = new StreamSubject<>();
        // Emit from a background thread so the transport can subscribe before
        // the first item is produced, exactly like the greeter's stream.
        Thread thread = new Thread(() -> {
            long a = 0;
            long b = 1;
            for (int i = 1; i <= count; i++) {
                observer.onNext(FibonacciReply.newBuilder()
                        .setIndex(i)
                        .setValue(a)
                        .build());
                long next = a + b;
                a = b;
                b = next;
            }
            observer.onCompleted();
        });
        thread.setDaemon(true);
        thread.start();
        return observer;
    }
}
