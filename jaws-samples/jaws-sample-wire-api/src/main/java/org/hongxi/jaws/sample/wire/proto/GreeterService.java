package org.hongxi.jaws.sample.wire.proto;

import java.util.concurrent.Flow;

/**
 * Service interface for the Greeter service.
 * <p>
 * Both parameter and return types are protobuf {@link com.google.protobuf.Message}
 * subclasses generated from {@code greeter.proto}. The {@code WireProtoTypes}
 * utility scans this interface to extract the request/response parsers.
 * <p>
 * Server-streaming methods return {@link Flow.Publisher Publisher&lt;HelloReply&gt;};
 * unary methods return {@link HelloReply} directly.
 */
public interface GreeterService {

    HelloReply sayHello(HelloRequest request);

    /**
     * Server-streaming: returns a {@link Flow.Publisher} that emits multiple
     * greeting messages for the given request.
     */
    Flow.Publisher<HelloReply> sayHelloStream(HelloRequest request);
}
