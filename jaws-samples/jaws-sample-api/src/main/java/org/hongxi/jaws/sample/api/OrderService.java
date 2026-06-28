package org.hongxi.jaws.sample.api;

import org.hongxi.jaws.sample.api.model.Order;
import org.hongxi.jaws.sample.api.model.User;

import java.util.List;

/**
 * 订单服务接口 - 用于演示多服务场景
 */
public interface OrderService {

    Order createOrder(User buyer, List<String> items);

    Order getOrder(Long orderId);

    List<Order> getOrdersByBuyer(User buyer);

    boolean cancelOrder(Long orderId);

    int countOrders();
}
