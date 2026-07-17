package org.hongxi.jaws.sample.provider.boot;

import org.hongxi.jaws.spring.boot.annotation.EnableJaws;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@EnableJaws
@SpringBootApplication
public class ProviderApplication {
    public static void main(String[] args) {
        SpringApplication.run(ProviderApplication.class, args);
    }
}
