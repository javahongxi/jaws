package org.hongxi.jaws.sample.wire.provider.service;

import org.hongxi.jaws.sample.wire.proto.GreeterService;
import org.hongxi.jaws.sample.wire.proto.HelloReply;
import org.hongxi.jaws.sample.wire.proto.HelloRequest;

/**
 * Greeter service implementation for the wire registry sample.
 */
public class GreeterServiceImpl implements GreeterService {

    @Override
    public HelloReply sayHello(HelloRequest request) {
        System.out.println("Received: " + request.getName());
        return HelloReply.newBuilder()
                .setMessage("Hello, " + request.getName() + "! (from jaws-wire)")
                .build();
    }
}
