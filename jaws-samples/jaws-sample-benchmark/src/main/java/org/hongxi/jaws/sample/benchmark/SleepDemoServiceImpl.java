package org.hongxi.jaws.sample.benchmark;

import org.hongxi.jaws.sample.api.DemoService;
import org.hongxi.jaws.sample.api.model.Contacts;
import org.hongxi.jaws.sample.api.model.User;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * DemoService decorator that simulates business processing time.
 *
 * <pre>
 * Sleeps the specified number of milliseconds before each invocation
 * to simulate real business processing latency.
 * Only decorates the hello method (benchmark only calls this method).
 * </pre>
 */
public class SleepDemoServiceImpl implements DemoService {

    private final DemoService delegate;
    private final long sleepMs;

    public SleepDemoServiceImpl(DemoService delegate, long sleepMs) {
        this.delegate = delegate;
        this.sleepMs = sleepMs;
    }

    @Override
    public String hello(String name) {
        if (sleepMs > 0) {
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return delegate.hello(name);
    }

    @Override
    public User rename(User user, String name) {
        return delegate.rename(user, name);
    }

    @Override
    public List<User> getUsers() {
        return delegate.getUsers();
    }

    @Override
    public Map<String, User> map(List<User> users) {
        return delegate.map(users);
    }

    @Override
    public void save(Contacts contacts) {
        delegate.save(contacts);
    }

    @Override
    public int save(List<Contacts> contactsList) {
        return delegate.save(contactsList);
    }

    @Override
    public CompletableFuture<String> helloAsync(String name) {
        return delegate.helloAsync(name);
    }

    @Override
    public CompletableFuture<User> getUserAsync(String name) {
        return delegate.getUserAsync(name);
    }
}
