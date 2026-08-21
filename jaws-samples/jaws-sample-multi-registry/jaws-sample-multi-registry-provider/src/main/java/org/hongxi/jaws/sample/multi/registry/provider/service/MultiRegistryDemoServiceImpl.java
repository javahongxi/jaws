package org.hongxi.jaws.sample.multi.registry.provider.service;

import org.hongxi.jaws.rpc.RpcContext;
import org.hongxi.jaws.sample.api.DemoService;
import org.hongxi.jaws.sample.api.model.Contacts;
import org.hongxi.jaws.sample.api.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * DemoService implementation for multi-registry sample.
 */
public class MultiRegistryDemoServiceImpl implements DemoService {
    private static final Logger log = LoggerFactory.getLogger(MultiRegistryDemoServiceImpl.class);

    @Override
    public String hello(String name) {
        log.info("Hello {}, request from: {}", name, RpcContext.getContext().getCallerIp());
        return "Hello, " + name + " [multi-registry provider]";
    }

    @Override
    public User rename(User user, String name) {
        user.setName(name);
        return user;
    }

    @Override
    public List<User> getUsers() {
        List<User> users = new ArrayList<>();
        users.add(new User("multi-reg-user-1", 30));
        users.add(new User("multi-reg-user-2", 25));
        return users;
    }

    @Override
    public Map<String, User> map(List<User> users) {
        Map<String, User> map = new HashMap<>();
        users.forEach(u -> map.put(u.getName(), u));
        return map;
    }

    @Override
    public void save(Contacts contacts) {
        log.info("save contacts: {}", contacts);
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
