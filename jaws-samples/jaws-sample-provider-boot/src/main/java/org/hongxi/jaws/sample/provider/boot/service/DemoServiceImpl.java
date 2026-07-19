package org.hongxi.jaws.sample.provider.boot.service;

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
 * Created by shenhongxi on 2021/4/25.
 */
@JawsService
public class DemoServiceImpl implements DemoService {
    private static final Logger log = LoggerFactory.getLogger(DemoServiceImpl.class);

    @Override
    public String hello(String name) {
        log.info("Hello {}, request from consumer: {}",
                name, RpcContext.getContext().getCallerIp());
        return "Hello, " + name;
    }

    @Override
    public User rename(User user, String name) {
        user.setName(name);
        return user;
    }

    @Override
    public List<User> getUsers() {
        List<User> users = new ArrayList<>();
        users.add(new User("lily", 24));
        users.add(new User("lucy", 25));
        return users;
    }

    @Override
    public Map<String, User> map(List<User> users) {
        Map<String, User> map = new HashMap<>();
        users.forEach(e -> map.put(e.getName(), e));
        return map;
    }

    @Override
    public void save(Contacts contacts) {
        System.out.println(contacts);
    }

    @Override
    public int save(List<Contacts> contactsList) {
        System.out.println(contactsList);
        return contactsList.size();
    }

    @Override
    public CompletableFuture<String> helloAsync(String name) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("helloAsync processing: {}", name);
            return "Hello async, " + name;
        });
    }
}
