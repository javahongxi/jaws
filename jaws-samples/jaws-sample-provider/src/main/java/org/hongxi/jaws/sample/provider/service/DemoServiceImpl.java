package org.hongxi.jaws.sample.provider.service;

import org.hongxi.jaws.sample.api.DemoService;
import org.hongxi.jaws.sample.api.model.Contacts;
import org.hongxi.jaws.sample.api.model.User;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by shenhongxi on 2021/4/25.
 */
public class DemoServiceImpl implements DemoService {

    @Override
    public String hello(String name) {
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
}
