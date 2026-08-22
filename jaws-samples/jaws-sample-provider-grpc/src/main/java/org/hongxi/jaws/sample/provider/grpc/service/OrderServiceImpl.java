package org.hongxi.jaws.sample.provider.grpc.service;

import org.hongxi.jaws.sample.api.OrderService;
import org.hongxi.jaws.sample.api.model.Order;
import org.hongxi.jaws.sample.api.model.User;
import org.hongxi.jaws.spring.boot.annotation.JawsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OrderService implementation exposed via gRPC transport.
 */
@JawsService
public class OrderServiceImpl implements OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderServiceImpl.class);

    private final AtomicLong idGenerator = new AtomicLong(1);
    private final Map<Long, Order> orderStore = new ConcurrentHashMap<>();

    @Override
    public Order createOrder(User buyer, List<String> items) {
        long id = idGenerator.getAndIncrement();
        Order order = new Order(id, "ORD-" + System.currentTimeMillis(), buyer, BigDecimal.valueOf(99.9 * items.size()));
        order.setItems(items);
        order.setCreateTime(LocalDateTime.now());
        orderStore.put(id, order);
        log.info("[grpc-provider] createOrder: {}", order);
        return order;
    }

    @Override
    public Order getOrder(Long orderId) {
        Order order = orderStore.get(orderId);
        log.info("[grpc-provider] getOrder: {}", order);
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
        log.info("[grpc-provider] getOrdersByBuyer: {} orders", result.size());
        return result;
    }

    @Override
    public boolean cancelOrder(Long orderId) {
        Order order = orderStore.get(orderId);
        if (order != null) {
            order.setStatus(-1);
            log.info("[grpc-provider] cancelOrder: {}", orderId);
            return true;
        }
        return false;
    }

    @Override
    public int countOrders() {
        int count = orderStore.size();
        log.info("[grpc-provider] countOrders: {}", count);
        return count;
    }
}
