package org.hongxi.jaws.sample.api;

import org.hongxi.jaws.sample.api.model.Contacts;
import org.hongxi.jaws.sample.api.model.User;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Created by shenhongxi on 2021/4/25.
 */
public interface DemoService {

    String hello(String name);

    User rename(User user, String name);

    List<User> getUsers();

    Map<String, User> map(List<User> users);

    void save(Contacts contacts);

    int save(List<Contacts> contactsList);

    /**
     * Async version of hello - demonstrates CompletableFuture integration.
     * Provider can return CompletableFuture for non-blocking processing.
     */
    CompletableFuture<String> helloAsync(String name);
}
