package org.hongxi.jaws.sample.provider.direct.service;

import org.hongxi.jaws.sample.api.OrderService;
import org.hongxi.jaws.sample.api.model.Order;
import org.hongxi.jaws.sample.api.model.User;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OrderService implementation for direct-mode provider.
 */
public class OrderServiceImpl implements OrderService {

    private final AtomicLong idGenerator = new AtomicLong(1);
    private final Map<Long, Order> orderStore = new ConcurrentHashMap<>();

    @Override
    public Order createOrder(User buyer, List<String> items) {
        long id = idGenerator.getAndIncrement();
        Order order = new Order(id, "ORD-" + System.currentTimeMillis(), buyer, BigDecimal.valueOf(99.9 * items.size()));
        order.setItems(items);
        order.setCreateTime(LocalDateTime.now());
        orderStore.put(id, order);
        System.out.println("[provider-direct] createOrder: " + order);
        return order;
    }

    @Override
    public Order getOrder(Long orderId) {
        Order order = orderStore.get(orderId);
        System.out.println("[provider-direct] getOrder: " + order);
        return order;
    }

    @Override
    public List<Order> getOrdersByBuyer(User buyer) {
        List<Order> result = new ArrayList<>();
        for (Order order : orderStore.values()) {
            if (order.getBuyer() != null && order.getBuyer().equals(buyer)) {
                result.add(order);
            }
        }
        System.out.println("[provider-direct] getOrdersByBuyer: " + result.size() + " orders");
        return result;
    }

    @Override
    public boolean cancelOrder(Long orderId) {
        Order order = orderStore.get(orderId);
        if (order != null) {
            order.setStatus(-1);
            System.out.println("[provider-direct] cancelOrder: " + orderId);
            return true;
        }
        return false;
    }

    @Override
    public int countOrders() {
        int count = orderStore.size();
        System.out.println("[provider-direct] countOrders: " + count);
        return count;
    }
}
