package org.hongxi.jaws.sample.stable.provider;

import org.hongxi.jaws.spring.boot.annotation.EnableJaws;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@EnableJaws
@SpringBootApplication
public class StableProviderApplication {
    public static void main(String[] args) {
        SpringApplication.run(StableProviderApplication.class, args);
    }
}
