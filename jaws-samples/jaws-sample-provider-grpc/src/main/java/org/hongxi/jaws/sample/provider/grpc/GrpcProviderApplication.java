package org.hongxi.jaws.sample.provider.grpc;

import org.hongxi.jaws.spring.boot.annotation.EnableJaws;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@EnableJaws
@SpringBootApplication
public class GrpcProviderApplication {
    public static void main(String[] args) {
        SpringApplication.run(GrpcProviderApplication.class, args);
    }
}
