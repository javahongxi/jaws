package org.hongxi.jaws.sample.http.rest.service;

import org.hongxi.jaws.sample.api.model.User;
import org.hongxi.jaws.sample.http.rest.UserService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory UserService implementation for the REST sample.
 */
public class UserServiceImpl implements UserService {

    private final Map<Long, User> store = new ConcurrentHashMap<>();
    private final AtomicLong idGen = new AtomicLong(1);

    public UserServiceImpl() {
        // seed data
        store.put(1L, new User("lily", 24));
        store.put(2L, new User("lucy", 25));
        idGen.set(3);
    }

    @Override
    public User getUser(Long id) {
        User user = store.get(id);
        if (user == null) {
            throw new IllegalArgumentException("User not found: " + id);
        }
        return user;
    }

    @Override
    public List<User> listUsers(Integer limit) {
        List<User> all = new ArrayList<>(store.values());
        if (limit != null && limit > 0 && limit < all.size()) {
            return all.subList(0, limit);
        }
        return all;
    }

    @Override
    public User createUser(User user) {
        long id = idGen.getAndIncrement();
        store.put(id, user);
        return user;
    }
}
