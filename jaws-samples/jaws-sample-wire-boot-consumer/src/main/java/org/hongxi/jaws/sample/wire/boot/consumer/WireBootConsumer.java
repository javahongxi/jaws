package org.hongxi.jaws.sample.wire.boot.consumer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Wire (gRPC wire format) consumer in Spring Boot mode, direct-connect demo.
 * <p>
 * Run {@code WireBootProvider} first, then browse/test:
 * <pre>
 *   curl 'http://localhost:8083/hello?name=World'
 *   curl -N 'http://localhost:8083/hello/stream?name=StreamUser'
 *   printf 'Alice\nBob\nCharlie\n' | curl -s -X POST --data-binary @- \
 *     -H 'Content-Type: text/plain' http://localhost:8083/greet/batch
 *   printf 'Alice\nBob\nCharlie\n' | curl -sN -X POST --data-binary @- \
 *     -H 'Content-Type: text/plain' http://localhost:8083/greet/bidi
 *   curl 'http://localhost:8083/fib?count=6'
 *   curl 'http://localhost:8083/divide?dividend=17&divisor=5'
 * </pre>
 */
@SpringBootApplication
public class WireBootConsumer {

    public static void main(String[] args) {
        SpringApplication.run(WireBootConsumer.class, args);
    }
}
