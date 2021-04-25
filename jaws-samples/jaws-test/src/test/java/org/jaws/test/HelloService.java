package org.jaws.test;

/**
 * Created by shenhongxi on 2021/4/25.
 */
public interface HelloService {

    String hello(String name);

    User rename(User user, String name);
}
