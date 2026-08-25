package org.hongxi.jaws.sample.stream.consumer;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.ReferenceConfig;
import org.hongxi.jaws.sample.api.StreamService;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/**
 * Stream-mode consumer - demonstrates server-streaming RPC consumption.
 *
 * <pre>
 * Demo scenario:
 * 1. jaws protocol + HTTP/2 transport (required for streaming)
 * 2. Subscribes to StreamService.greetStream() Flow.Publisher
 * 3. directUrl mode - connects to provider without registry
 * </pre>
 *
 * <p>Run {@code StreamProvider} first before starting this consumer.
 */
public class StreamConsumer {

    private static final String DIRECT_URL = System.getProperty("directUrl", "127.0.0.1:10000");
    private static final String SERIALIZATION = System.getProperty("serialization", "fastjson2");

    public static void main(String[] args) throws Exception {
        ProtocolConfig protocolConfig = createProtocolConfig();

        /* Reference StreamService with directUrl */
        ReferenceConfig<StreamService> streamRef = new ReferenceConfig<>();
        streamRef.setInterface(StreamService.class);
        streamRef.setApplication("sample-stream-consumer");
        streamRef.setModule("sample-stream");
        streamRef.setGroup("test");
        streamRef.setVersion("2.0");
        streamRef.setProtocol(protocolConfig);
        streamRef.setDirectUrl(DIRECT_URL);

        StreamService streamService = streamRef.getRef();

        /* Server-streaming invocation */
        System.out.println("--- StreamService server streaming ---");
        Flow.Publisher<String> publisher = streamService.greetStream("hello", 5);
        CountDownLatch streamLatch = new CountDownLatch(1);

        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(String item) {
                System.out.println("stream item => " + item);
            }

            @Override
            public void onError(Throwable throwable) {
                System.err.println("stream error => " + throwable.getMessage());
                streamLatch.countDown();
            }

            @Override
            public void onComplete() {
                streamLatch.countDown();
            }
        });

        try {
            streamLatch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.out.println("stream completed.");

        /* Exit forcibly (Netty non-daemon threads would prevent JVM from exiting) */
        System.exit(0);
    }

    private static ProtocolConfig createProtocolConfig() {
        ProtocolConfig protocolConfig = new ProtocolConfig();
        protocolConfig.setName(JawsConstants.PROTOCOL_JAWS);
        protocolConfig.setId(protocolConfig.getName());
        protocolConfig.setTransportFactory("http2");
        protocolConfig.setSerialization(SERIALIZATION);
        return protocolConfig;
    }
}
