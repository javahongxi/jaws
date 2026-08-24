package org.hongxi.jaws.sample.provider.http2;

import org.hongxi.jaws.spring.boot.annotation.EnableJaws;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@EnableJaws
@SpringBootApplication
public class Http2ProviderApplication {
    public static void main(String[] args) {
        SpringApplication.run(Http2ProviderApplication.class, args);
    }
}
