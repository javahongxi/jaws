package org.hongxi.jaws.sample.consumer.boot;

import org.hongxi.jaws.sample.api.DemoService;
import org.hongxi.jaws.spring.boot.annotation.JawsReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ConsumerController {
    private static final Logger log = LoggerFactory.getLogger(ConsumerController.class);

    @JawsReference
    private DemoService demoService;

    @GetMapping("/hello")
    public String hello(@RequestParam("name") String name) {
        log.info("Calling jaws provider, {}", name);
        return demoService.hello(name);
    }
}
