package org.hongxi.jaws.sample.http2.provider.service;

import org.hongxi.jaws.sample.api.StreamService;

import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;

/**
 * StreamService implementation for the HTTP/2 provider sample.
 */
public class StreamServiceImpl implements StreamService {

    @Override
    public Flow.Publisher<String> greetStream(String prefix, int count) {
        return subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                private int sent = 0;

                @Override
                public void request(long n) {
                    for (long i = 0; i < n && sent < count; i++, sent++) {
                        subscriber.onNext(prefix + "-" + sent);
                    }
                    if (sent >= count) {
                        subscriber.onComplete();
                    }
                }

                @Override
                public void cancel() {
                }
            });
        };
    }

    @Override
    public Flow.Publisher<String> bidiGreet(Flow.Publisher<String> names) {
        SubmissionPublisher<String> responsePublisher = new SubmissionPublisher<>();

        names.subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription s) {
                this.subscription = s;
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(String name) {
                System.out.println("bidiGreet received: " + name);
                responsePublisher.submit("Hello, " + name + "! (from bidi stream)");
            }

            @Override
            public void onError(Throwable throwable) {
                System.err.println("bidiGreet request stream error: " + throwable.getMessage());
                responsePublisher.closeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                System.out.println("bidiGreet request stream completed.");
                responsePublisher.close();
            }
        });

        return responsePublisher;
    }
}
