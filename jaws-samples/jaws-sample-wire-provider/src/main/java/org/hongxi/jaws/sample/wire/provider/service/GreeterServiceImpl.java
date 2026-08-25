package org.hongxi.jaws.sample.wire.provider.service;

import org.hongxi.jaws.sample.wire.proto.GreeterService;
import org.hongxi.jaws.sample.wire.proto.HelloReply;
import org.hongxi.jaws.sample.wire.proto.HelloRequest;

import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;

/**
 * Greeter service implementation for the wire sample.
 */
public class GreeterServiceImpl implements GreeterService {

    @Override
    public HelloReply sayHello(HelloRequest request) {
        System.out.println("Received: " + request.getName());
        return HelloReply.newBuilder()
                .setMessage("Hello, " + request.getName() + "! (from jaws-wire)")
                .build();
    }

    @Override
    public Flow.Publisher<HelloReply> sayHelloStream(HelloRequest request) {
        System.out.println("Received streaming request: " + request.getName());
        SubmissionPublisher<HelloReply> publisher = new SubmissionPublisher<>();
        // Emit 3 greeting messages asynchronously
        Thread thread = new Thread(() -> {
            for (int i = 1; i <= 3; i++) {
                publisher.submit(HelloReply.newBuilder()
                        .setMessage("Hello #" + i + ", " + request.getName() + "! (from jaws-wire stream)")
                        .build());
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            publisher.close();
        });
        thread.setDaemon(true);
        thread.start();
        return publisher;
    }
}
