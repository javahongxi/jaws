package org.hongxi.jaws.sample.api;

import org.hongxi.jaws.sample.api.model.Contacts;
import org.hongxi.jaws.sample.api.model.User;

import java.util.List;
import java.util.Map;

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
}
