package org.jaws.test;

import org.hongxi.jaws.sample.api.HelloService;

public class HelloServiceImpl implements HelloService {

    @Override
    public String hello(String name) {
        return "Hello, " + name;
    }
}