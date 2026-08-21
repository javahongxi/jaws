package org.hongxi.jaws.sample.gray.consumer;

import org.hongxi.jaws.rpc.RpcContext;
import org.hongxi.jaws.sample.api.DemoService;
import org.hongxi.jaws.spring.boot.annotation.JawsReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consumer controller demonstrating tag-based routing for gray release.
 * <p>
 * Three endpoints:
 * <ul>
 *   <li>{@code /hello} — normal call, routes to any provider (tagged or untagged)</li>
 *   <li>{@code /hello/gray} — sets tag=gray attachment, routes only to gray-tagged providers</li>
 *   <li>{@code /hello/stable} — sets tag=stable attachment, routes only to stable-tagged providers</li>
 * </ul>
 */
@RestController
public class GrayConsumerController {
    private static final Logger log = LoggerFactory.getLogger(GrayConsumerController.class);

    @JawsReference
    private DemoService demoService;

    /**
     * Normal invocation — no tag, routes to all available providers.
     */
    @GetMapping("/hello")
    public String hello(@RequestParam("name") String name) {
        log.info("Normal call: hello({})", name);
        return demoService.hello(name);
    }

    /**
     * Gray-tagged invocation — only reaches providers with tag=gray.
     */
    @GetMapping("/hello/gray")
    public String helloGray(@RequestParam("name") String name) {
        log.info("Gray call: hello({})", name);
        try {
            RpcContext.getContext().setRpcAttachment("tag", "gray");
            return demoService.hello(name);
        } finally {
            RpcContext.getContext().removeRpcAttachment("tag");
        }
    }

    /**
     * Stable-tagged invocation — only reaches providers with tag=stable.
     */
    @GetMapping("/hello/stable")
    public String helloStable(@RequestParam("name") String name) {
        log.info("Stable call: hello({})", name);
        try {
            RpcContext.getContext().setRpcAttachment("tag", "stable");
            return demoService.hello(name);
        } finally {
            RpcContext.getContext().removeRpcAttachment("tag");
        }
    }
}
