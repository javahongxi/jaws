package org.hongxi.jaws.sample.stream.provider.service;

import org.hongxi.jaws.sample.api.StreamService;

import java.util.concurrent.Flow;

/**
 * StreamService implementation for stream-mode provider.
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
}
