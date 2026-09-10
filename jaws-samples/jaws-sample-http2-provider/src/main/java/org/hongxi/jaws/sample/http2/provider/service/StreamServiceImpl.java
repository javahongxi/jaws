package org.hongxi.jaws.sample.http2.provider.service;

import org.hongxi.jaws.sample.api.StreamService;
import org.hongxi.jaws.transport.StreamSubject;
import org.hongxi.jaws.stream.StreamObserver;
import org.hongxi.jaws.stream.StreamSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * StreamService implementation for the HTTP/2 provider sample.
 */
public class StreamServiceImpl implements StreamService {

    @Override
    public StreamSource<String> greetStream(String prefix, int count) {
        StreamSubject<String> observer = new StreamSubject<>();
        for (int i = 0; i < count; i++) {
            observer.onNext(prefix + "-" + i);
        }
        observer.onCompleted();
        return observer;
    }

    @Override
    public String collectGreet(StreamSource<String> names) {
        // Consume the request stream and collect every name until it completes.
        List<String> collected = new ArrayList<>();
        CompletableFuture<Void> done = new CompletableFuture<>();

        names.subscribe(new StreamObserver<>() {
            @Override
            public void onNext(String name) {
                System.out.println("collectGreet received: " + name);
                collected.add(name);
            }

            @Override
            public void onError(Throwable throwable) {
                System.err.println("collectGreet request stream error: " + throwable.getMessage());
                done.completeExceptionally(throwable);
            }

            @Override
            public void onCompleted() {
                System.out.println("collectGreet request stream completed. names=" + collected);
                done.complete(null);
            }
        });

        try {
            done.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("collectGreet failed", e);
        }
        return "Hello, " + String.join(" & ", collected) + "! (from client stream)";
    }

    @Override
    public StreamSource<String> bidiGreet(StreamSource<String> names) {
        StreamSubject<String> responseObserver = new StreamSubject<>();

        names.subscribe(new StreamObserver<>() {
            @Override
            public void onNext(String name) {
                System.out.println("bidiGreet received: " + name);
                responseObserver.onNext("Hello, " + name + "! (from bidi stream)");
            }

            @Override
            public void onError(Throwable throwable) {
                System.err.println("bidiGreet request stream error: " + throwable.getMessage());
                responseObserver.onError(throwable);
            }

            @Override
            public void onCompleted() {
                System.out.println("bidiGreet request stream completed.");
                responseObserver.onCompleted();
            }
        });

        return responseObserver;
    }
}
