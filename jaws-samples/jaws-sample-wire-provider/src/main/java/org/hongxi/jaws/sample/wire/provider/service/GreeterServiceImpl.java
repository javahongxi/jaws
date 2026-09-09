package org.hongxi.jaws.sample.wire.provider.service;

import org.hongxi.jaws.rpc.RpcContext;
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
        // gRPC metadata sent by the caller arrives as request attachments
        String traceId = RpcContext.getContext().getRpcAttachment("x-trace-id");
        System.out.println("Received: " + request.getName()
                + (traceId != null ? ", x-trace-id=" + traceId : ""));
        return HelloReply.newBuilder()
                .setMessage("Hello, " + request.getName() + "! (from jaws-wire)")
                .build();
    }

    @Override
    public Flow.Publisher<HelloReply> sayHelloStream(HelloRequest request) {
        System.out.println("Received streaming request: " + request.getName());
        SubmissionPublisher<HelloReply> publisher = new SubmissionPublisher<>();
        // Defer emission until subscription: SubmissionPublisher drops items
        // submitted while there are no subscribers, and the framework only
        // subscribes after this method returns. Emitting first would race
        // the framework's subscribe and could drop early items.
        return subscriber -> {
            publisher.subscribe(subscriber);
            // Emission starts after the subscription is registered
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
        };
    }

    @Override
    public Flow.Publisher<HelloReply> bidiGreet(Flow.Publisher<HelloRequest> names) {
        System.out.println("Bidi greet: subscribing to request stream");
        SubmissionPublisher<HelloReply> responsePublisher = new SubmissionPublisher<>();

        names.subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription s) {
                this.subscription = s;
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(HelloRequest item) {
                System.out.println("Bidi received: " + item.getName());
                responsePublisher.submit(HelloReply.newBuilder()
                        .setMessage("Hello, " + item.getName() + "! (from jaws-wire bidi)")
                        .build());
            }

            @Override
            public void onError(Throwable throwable) {
                System.err.println("Bidi stream error: " + throwable.getMessage());
                responsePublisher.closeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                System.out.println("Bidi request stream completed");
                responsePublisher.close();
            }
        });

        return responsePublisher;
    }
}
