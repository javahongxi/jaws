package org.hongxi.jaws.sample.consumer.boot;

import org.hongxi.jaws.sample.api.DemoService;
import org.hongxi.jaws.spring.boot.annotation.JawsReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;

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

    /**
     * CompletableFuture 异步调用示例
     * <p>
     * 接口方法返回 CompletableFuture，框架自动桥接 RPC 异步响应到 CompletableFuture，
     * 不阻塞 Servlet 线程，支持 thenAccept / exceptionally 等编排。
     */
    @GetMapping("/helloAsync")
    public CompletableFuture<String> helloAsync(@RequestParam("name") String name) {
        log.info("Async calling jaws provider, {}", name);
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
}
