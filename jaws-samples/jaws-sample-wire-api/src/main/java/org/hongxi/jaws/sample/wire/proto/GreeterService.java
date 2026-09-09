package org.hongxi.jaws.sample.wire.proto;

import org.hongxi.jaws.stream.StreamSource;

/**
 * Service interface for the Greeter service.
 * <p>
 * Both parameter and return types are protobuf {@link com.google.protobuf.Message}
 * subclasses generated from {@code greeter.proto}. The {@code WireProtoTypes}
 * utility scans this interface to extract the request/response parsers.
 * <p>
 * Server-streaming methods return {@link StreamSource Source&lt;HelloReply&gt;};
 * unary methods return {@link HelloReply} directly.
 */
public interface GreeterService {

    HelloReply sayHello(HelloRequest request);

    /**
     * Server-streaming: returns a {@link StreamSource} that emits multiple
     * greeting messages for the given request.
     */
    StreamSource<HelloReply> sayHelloStream(HelloRequest request);

    /**
     * Client-streaming: client streams {@link HelloRequest} names,
     * server replies with a single aggregated {@link HelloReply}.
     */
    HelloReply clientStreamGreet(StreamSource<HelloRequest> names);

    /**
     * Bidirectional streaming: client streams {@link HelloRequest} names,
     * server streams {@link HelloReply} greetings.
     */
    StreamSource<HelloReply> bidiGreet(StreamSource<HelloRequest> names);
}
