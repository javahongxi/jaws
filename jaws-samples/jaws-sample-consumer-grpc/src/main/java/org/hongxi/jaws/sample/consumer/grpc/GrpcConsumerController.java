package org.hongxi.jaws.sample.consumer.grpc;

import org.hongxi.jaws.sample.api.DemoService;
import org.hongxi.jaws.sample.api.OrderService;
import org.hongxi.jaws.spring.boot.annotation.JawsReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;

@RestController
public class GrpcConsumerController {
    private static final Logger log = LoggerFactory.getLogger(GrpcConsumerController.class);

    @JawsReference
    private DemoService demoService;

    @JawsReference
    private OrderService orderService;

    @GetMapping("/hello")
    public String hello(@RequestParam("name") String name) {
        log.info("Calling DemoService via gRPC, name={}", name);
        return demoService.hello(name);
    }

    @GetMapping("/helloAsync")
    public CompletableFuture<String> helloAsync(@RequestParam("name") String name) {
        log.info("Async calling DemoService via gRPC, name={}", name);
        return demoService.helloAsync(name)
                .thenApply(result -> {
                    log.info("Async result received: {}", result);
                    return result;
                })
                .exceptionally(ex -> {
                    log.error("Async call failed", ex);
                    return "Error: " + ex.getMessage();
                });
    }

    @GetMapping("/orders/count")
    public int countOrders() {
        log.info("Calling OrderService via gRPC");
        return orderService.countOrders();
    }
}
