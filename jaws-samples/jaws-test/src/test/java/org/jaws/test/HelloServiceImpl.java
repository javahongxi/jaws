package org.jaws.test;

public class HelloServiceImpl implements HelloService {

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