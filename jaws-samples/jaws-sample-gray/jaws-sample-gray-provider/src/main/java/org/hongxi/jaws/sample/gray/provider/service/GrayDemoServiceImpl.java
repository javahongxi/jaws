package org.hongxi.jaws.sample.gray.provider.service;

import org.hongxi.jaws.rpc.RpcContext;
import org.hongxi.jaws.sample.api.DemoService;
import org.hongxi.jaws.sample.api.model.Contacts;
import org.hongxi.jaws.sample.api.model.User;
import org.hongxi.jaws.spring.boot.annotation.JawsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * DemoService implementation for gray release sample.
 * <p>
 * The {@code tag} attribute is read from the annotation or from the global property
 * {@code jaws.service.tag}. When running with {@code --jaws.service.tag=gray}, this
 * provider registers with tag=gray and is only reachable by consumers that set the
 * same tag attachment.
 * <p>
 * The response includes the tag so the consumer can verify which instance was routed to.
 */
@JawsService
public class GrayDemoServiceImpl implements DemoService {
    private static final Logger log = LoggerFactory.getLogger(GrayDemoServiceImpl.class);

    @Override
    public String hello(String name) {
        String tag = RpcContext.getContext().getRpcAttachment("tag");
        String response = "Hello, " + name + " [from tag=" + (tag != null ? tag : "stable") + "]";
        log.info("hello({}) => {}, caller={}", name, response, RpcContext.getContext().getCallerIp());
        return response;
    }

    @Override
    public User rename(User user, String name) {
        user.setName(name);
        return user;
    }

    @Override
    public List<User> getUsers() {
        return new ArrayList<>();
    }

    @Override
    public Map<String, User> map(List<User> users) {
        return new HashMap<>();
    }

    @Override
    public void save(Contacts contacts) {
    }

    @Override
    public int save(List<Contacts> contactsList) {
        return contactsList.size();
    }

    @Override
    public CompletableFuture<String> helloAsync(String name) {
        return CompletableFuture.supplyAsync(() -> hello(name));
    }
}
