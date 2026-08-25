package org.hongxi.jaws.sample.wire.proto;

/**
 * Service interface for the Greeter service.
 * <p>
 * Both parameter and return types are protobuf {@link com.google.protobuf.Message}
 * subclasses generated from {@code greeter.proto}. The {@code WireProtoTypes}
 * utility scans this interface to extract the request/response parsers.
 */
public interface GreeterService {

    HelloReply sayHello(HelloRequest request);
}
