package org.hongxi.jaws.sample.provider.service;

import org.hongxi.jaws.sample.api.DemoService;
import org.hongxi.jaws.sample.api.model.User;

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
}
