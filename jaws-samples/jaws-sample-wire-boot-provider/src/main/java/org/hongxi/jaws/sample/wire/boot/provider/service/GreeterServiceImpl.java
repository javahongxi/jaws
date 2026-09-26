package org.hongxi.jaws.sample.wire.boot.provider.service;

import org.hongxi.jaws.rpc.RpcContext;
import org.hongxi.jaws.sample.wire.proto.GreeterService;
import org.hongxi.jaws.sample.wire.proto.HelloReply;
import org.hongxi.jaws.sample.wire.proto.HelloRequest;
import org.hongxi.jaws.spring.boot.annotation.JawsService;
import org.hongxi.jaws.stream.StreamObserver;
import org.hongxi.jaws.stream.StreamSource;
import org.hongxi.jaws.transport.StreamSubject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Greeter service exported through the wire protocol by the Spring Boot starter.
 * <p>
 * Same business logic as the plain wire sample, only the publication mechanism
 * differs: the {@code @JawsService} bean is picked up by {@code @EnableJaws}
 * scanning and exported on context refresh.
 */
@JawsService
public class GreeterServiceImpl implements GreeterService {
    private static final Logger log = LoggerFactory.getLogger(GreeterServiceImpl.class);

    @Override
    public HelloReply sayHello(HelloRequest request) {
        // gRPC metadata sent by the caller arrives as request attachments
        String traceId = RpcContext.getContext().getRpcAttachment("x-trace-id");
        log.info("Hello {}, x-trace-id={}", request.getName(), traceId);
        return HelloReply.newBuilder()
                .setMessage("Hello, " + request.getName() + "! (from jaws-wire-boot)")
                .build();
    }

    @Override
    public StreamSource<HelloReply> sayHelloStream(HelloRequest request) {
        log.info("Received streaming request: {}", request.getName());
        StreamSubject<HelloReply> observer = new StreamSubject<>();
        // Emit items in a background thread to simulate async production
        Thread thread = new Thread(() -> {
            for (int i = 1; i <= 3; i++) {
                observer.onNext(HelloReply.newBuilder()
                        .setMessage("Hello #" + i + ", " + request.getName() + "! (from jaws-wire-boot stream)")
                        .build());
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            observer.onCompleted();
        });
        thread.setDaemon(true);
        thread.start();
        return observer;
    }

    @Override
    public HelloReply clientStreamGreet(StreamSource<HelloRequest> names) {
        log.info("Client stream greet: subscribing to request stream");
        List<String> collectedNames = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        names.subscribe(new StreamObserver<>() {
            @Override
            public void onNext(HelloRequest item) {
                log.info("Client stream received: {}", item.getName());
                collectedNames.add(item.getName());
            }

            @Override
            public void onError(Throwable throwable) {
                error.set(throwable);
                latch.countDown();
            }

            @Override
            public void onCompleted() {
                latch.countDown();
            }
        });

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while collecting client stream", e);
        }
        if (error.get() != null) {
            throw new RuntimeException("Client stream failed", error.get());
        }

        String namesList = String.join(", ", collectedNames);
        log.info("Client stream completed, names: {}", namesList);
        return HelloReply.newBuilder()
                .setMessage("Hello, " + namesList + "! (from jaws-wire-boot client-stream)")
                .build();
    }

    @Override
    public StreamSource<HelloReply> bidiGreet(StreamSource<HelloRequest> names) {
        log.info("Bidi greet: subscribing to request stream");
        StreamSubject<HelloReply> responseObserver = new StreamSubject<>();

        names.subscribe(new StreamObserver<>() {
            @Override
            public void onNext(HelloRequest item) {
                log.info("Bidi received: {}", item.getName());
                responseObserver.onNext(HelloReply.newBuilder()
                        .setMessage("Hello, " + item.getName() + "! (from jaws-wire-boot bidi)")
                        .build());
            }

            @Override
            public void onError(Throwable throwable) {
                log.error("Bidi stream error", throwable);
                responseObserver.onError(throwable);
            }

            @Override
            public void onCompleted() {
                log.info("Bidi request stream completed");
                responseObserver.onCompleted();
            }
        });

        return responseObserver;
    }
}
