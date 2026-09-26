package org.hongxi.jaws.sample.wire.boot.consumer;

import org.hongxi.jaws.rpc.RpcContext;
import org.hongxi.jaws.sample.wire.proto.CalculatorService;
import org.hongxi.jaws.sample.wire.proto.DivideReply;
import org.hongxi.jaws.sample.wire.proto.DivideRequest;
import org.hongxi.jaws.sample.wire.proto.FibonacciReply;
import org.hongxi.jaws.sample.wire.proto.FibonacciRequest;
import org.hongxi.jaws.sample.wire.proto.GreeterService;
import org.hongxi.jaws.sample.wire.proto.HelloReply;
import org.hongxi.jaws.sample.wire.proto.HelloRequest;
import org.hongxi.jaws.spring.boot.annotation.JawsReference;
import org.hongxi.jaws.stream.StreamObserver;
import org.hongxi.jaws.stream.StreamSource;
import org.hongxi.jaws.transport.StreamSubject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;

/**
 * REST bridge over wire-protocol RPC references, resolved by {@code directUrl}
 * (no registry discovery) — the Spring-annotation flavour of {@code WireConsumer}.
 * <p>
 * All four gRPC shapes are covered: unary maps to plain request/response,
 * server streaming to SSE, client streaming and bidirectional streaming to a
 * {@code text/plain} body whose lines are pushed one by one through a
 * {@link StreamSubject}. True item-by-item pacing on the inbound side needs a
 * streaming HTTP client (or the plain wire consumer, which feeds the subject
 * from a sender thread) — over HTTP/1.1 the request body is read in bulk, so
 * what the bridge demonstrates here is the RPC contract and the streaming
 * reply path.
 */
@RestController
public class ConsumerController {
    private static final Logger log = LoggerFactory.getLogger(ConsumerController.class);

    @Value("${sample.wire.address}")
    private String wireAddress;

    /** Sentinel pushed into the queue when a stream finishes. */
    private static final Object STREAM_DONE = new Object();

    @JawsReference(interfaceClass = GreeterService.class, directUrl = "${sample.wire.address}")
    private GreeterService greeterService;

    @JawsReference(interfaceClass = CalculatorService.class, directUrl = "${sample.wire.address}")
    private CalculatorService calculatorService;

    @GetMapping("/hello")
    public String hello(@RequestParam("name") String name) {
        log.info("Calling wire provider at {}, name={}", wireAddress, name);
        // RpcContext attachments travel as gRPC custom headers (metadata)
        RpcContext.getContext().setRpcAttachment("x-trace-id", "boot-" + System.currentTimeMillis());
        HelloReply reply = greeterService.sayHello(HelloRequest.newBuilder().setName(name).build());
        return reply.getMessage();
    }

    /**
     * Server streaming bridged to SSE: each {@link HelloReply} becomes one
     * {@code data:} line as it arrives on the wire.
     */
    @GetMapping(value = "/hello/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public StreamingResponseBody helloStream(@RequestParam("name") String name) {
        return toSse(greeterService.sayHelloStream(HelloRequest.newBuilder().setName(name).build()));
    }

    /**
     * Client streaming: one name per request line, pushed to the provider as
     * separate {@link HelloRequest} messages; the provider aggregates them and
     * replies with a single greeting.
     */
    @PostMapping(value = "/greet/batch", consumes = MediaType.TEXT_PLAIN_VALUE)
    public String greetBatch(@RequestBody String names) {
        StreamSubject<HelloRequest> requests = new StreamSubject<>();
        for (String name : toLines(names)) {
            requests.onNext(HelloRequest.newBuilder().setName(name).build());
        }
        requests.onCompleted();
        return greeterService.clientStreamGreet(requests).getMessage();
    }

    /**
     * Bidirectional streaming: names go in line by line, each greeting comes
     * out as its own SSE event as soon as it arrives on the wire.
     */
    @PostMapping(value = "/greet/bidi", consumes = MediaType.TEXT_PLAIN_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public StreamingResponseBody greetBidi(@RequestBody String names) {
        StreamSubject<HelloRequest> requests = new StreamSubject<>();
        for (String name : toLines(names)) {
            requests.onNext(HelloRequest.newBuilder().setName(name).build());
        }
        requests.onCompleted();
        return toSse(greeterService.bidiGreet(requests));
    }

    @GetMapping("/fib")
    public List<String> fib(@RequestParam("count") int count) {
        // Collect the whole stream into a list — blocking collect for demo purposes
        List<FibonacciReply> replies = toList(
                calculatorService.fibonacci(FibonacciRequest.newBuilder().setCount(count).build()));
        List<String> result = new ArrayList<>();
        for (FibonacciReply reply : replies) {
            result.add("F" + reply.getIndex() + " = " + reply.getValue());
        }
        return result;
    }

    @GetMapping("/divide")
    public String divide(@RequestParam("dividend") long dividend, @RequestParam("divisor") long divisor) {
        DivideReply reply = calculatorService.divide(DivideRequest.newBuilder()
                .setDividend(dividend)
                .setDivisor(divisor)
                .build());
        return dividend + " / " + divisor + " = " + reply.getQuotient()
                + " ... " + reply.getRemainder();
    }

    /** Split a {@code text/plain} body into stream items, ignoring blank lines. */
    private static List<String> toLines(String body) {
        return body.lines().map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
    }

    /**
     * Bridge a server-side {@link StreamSource} to SSE: subscribe on the caller
     * thread, then let the Servlet async thread drain the queue and write each
     * item as soon as it shows up ({@link StreamSubject} replays everything
     * produced before the subscription, so no greeting can be lost).
     */
    private static StreamingResponseBody toSse(StreamSource<HelloReply> source) {
        BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
        source.subscribe(new StreamObserver<>() {
            @Override
            public void onNext(HelloReply item) {
                queue.add(item.getMessage());
            }

            @Override
            public void onError(Throwable throwable) {
                queue.add(throwable);
            }

            @Override
            public void onCompleted() {
                queue.add(STREAM_DONE);
            }
        });
        return outputStream -> {
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream));
            try {
                while (true) {
                    Object item = queue.take();
                    if (item == STREAM_DONE) {
                        break;
                    }
                    if (item instanceof Throwable) {
                        throw new IOException("Stream error", (Throwable) item);
                    }
                    writer.write("data: " + item + "\n\n");
                    writer.flush();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }

    /** Block until the stream completes and buffer all items (demo helper). */
    private static <T> List<T> toList(StreamSource<T> source) {
        List<T> items = new ArrayList<>();
        BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
        source.subscribe(new StreamObserver<>() {
            @Override
            public void onNext(T item) {
                items.add(item);
            }

            @Override
            public void onError(Throwable throwable) {
                queue.add(throwable);
            }

            @Override
            public void onCompleted() {
                queue.add(STREAM_DONE);
            }
        });
        try {
            Object signal = queue.take();
            if (signal instanceof Throwable) {
                throw new IllegalStateException("Stream failed", (Throwable) signal);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return items;
    }
}
