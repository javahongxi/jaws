package org.hongxi.jaws.sample.mcp.service;

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
 * DemoService implementation for MCP sample.
 * <p>
 * All methods of this service are automatically registered as MCP Tools
 * when the application starts with jaws-mcp enabled.
 */
@JawsService
public class DemoServiceImpl implements DemoService {

    private static final Logger log = LoggerFactory.getLogger(DemoServiceImpl.class);

    @Override
    public String hello(String name) {
        log.info("[MCP-Demo] hello({})", name);
        return "Hello, " + name;
    }

    @Override
    public User rename(User user, String name) {
        log.info("[MCP-Demo] rename({}, {})", user, name);
        user.setName(name);
        return user;
    }

    @Override
    public List<User> getUsers() {
        log.info("[MCP-Demo] getUsers()");
        List<User> users = new ArrayList<>();
        users.add(new User("lily", 24));
        users.add(new User("lucy", 25));
        return users;
    }

    @Override
    public Map<String, User> map(List<User> users) {
        log.info("[MCP-Demo] map({})", users);
        Map<String, User> map = new HashMap<>();
        users.forEach(e -> map.put(e.getName(), e));
        return map;
    }

    @Override
    public void save(Contacts contacts) {
        log.info("[MCP-Demo] save(contacts): {}", contacts);
    }

    @Override
    public int save(List<Contacts> contactsList) {
        log.info("[MCP-Demo] save(contactsList): size={}", contactsList.size());
        return contactsList.size();
    }

    @Override
    public CompletableFuture<String> helloAsync(String name) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("[MCP-Demo] helloAsync({})", name);
            return "Hello async, " + name;
        });
    }
}
