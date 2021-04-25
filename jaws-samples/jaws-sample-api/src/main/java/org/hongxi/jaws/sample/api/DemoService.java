package org.hongxi.jaws.sample.api;

import org.hongxi.jaws.sample.api.model.User;

/**
 * Created by shenhongxi on 2021/4/25.
 */
public interface DemoService {

    String hello(String name);

    User rename(User user, String name);
}
