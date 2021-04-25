package org.hongxi.jaws.sample.provider.service;

import org.hongxi.jaws.sample.api.HelloService;

/**
 * Created by shenhongxi on 2021/4/25.
 */
public class HelloServiceImpl implements HelloService {
    @Override
    public String hello(String name) {
        return "Hello, " + name;
    }
}
