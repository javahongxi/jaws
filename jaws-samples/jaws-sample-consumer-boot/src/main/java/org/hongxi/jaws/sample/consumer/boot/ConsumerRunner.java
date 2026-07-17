package org.hongxi.jaws.sample.consumer.boot;

import org.hongxi.jaws.sample.api.DemoService;
import org.hongxi.jaws.spring.boot.annotation.JawsReference;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class ConsumerRunner implements CommandLineRunner {

    @JawsReference
    private DemoService demoService;

    @Override
    public void run(String... args) throws Exception {
        String result = demoService.hello("lily");
        System.out.println("result: " + result);
    }
}
