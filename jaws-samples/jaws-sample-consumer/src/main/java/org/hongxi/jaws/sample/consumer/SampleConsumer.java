package org.hongxi.jaws.sample.consumer;

import com.google.common.collect.Lists;
import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.ReferenceConfig;
import org.hongxi.jaws.config.RegistryConfig;
import org.hongxi.jaws.rpc.RpcContext;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.sample.api.DemoService;
import org.hongxi.jaws.sample.api.OrderService;
import org.hongxi.jaws.sample.api.model.Contacts;
import org.hongxi.jaws.sample.api.model.Order;
import org.hongxi.jaws.sample.api.model.Phone;
import org.hongxi.jaws.sample.api.model.User;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 服务消费者示例
 *
 * <pre>
 * 演示场景：
 * 1. jaws 协议 + ZooKeeper 注册中心
 * 2. 多服务引用 - DemoService + OrderService
 * 3. 各种参数类型调用 - String、POJO、List、Map、嵌套对象
 * 4. group/version 配置
 * </pre>
 *
 * 启动前请先运行 SampleProvider 确保服务已导出
 */
public class SampleConsumer {

    public static void main(String[] args) throws Exception {
        ProtocolConfig protocolConfig = createProtocolConfig(JawsConstants.PROTOCOL_JAWS);
        RegistryConfig registryConfig = createRegistryConfig(JawsConstants.REGISTRY_PROTOCOL_ZOOKEEPER);

        /* 引用 DemoService */
        ReferenceConfig<DemoService> demoRef = new ReferenceConfig<>();
        demoRef.setInterface(DemoService.class);
        demoRef.setApplication("sample-consumer");
        demoRef.setModule("sample");
        demoRef.setGroup("test");
        demoRef.setRequestTimeout(2000);
        demoRef.setVersion("2.0");
        demoRef.setCheck("false");
        demoRef.setProtocol(protocolConfig);
        demoRef.setRegistry(registryConfig);

        DemoService demoService = demoRef.getRef();

        /* 基本调用 */
        System.out.println("--- DemoService 基本调用 ---");
        String r = demoService.hello("lily");
        System.out.println("hello => " + r);

        /* 打印实际调用的服务端地址 */
        URL serverUrl = RpcContext.getContext().getServerUrl();
        if (serverUrl != null) {
            System.out.println("server => " + serverUrl.getHost() + ":" + serverUrl.getPort());
        }

        User user = new User("lily", 24);
        User newUser = demoService.rename(user, "lucy");
        System.out.println("rename => " + newUser);

        /* 复杂参数调用 */
        System.out.println("\n--- DemoService 复杂参数 ---");
        List<User> users = demoService.getUsers();
        System.out.println("getUsers => " + users);

        Map<String, User> map = demoService.map(users);
        System.out.println("map => " + map);

        Contacts contacts = new Contacts();
        contacts.setId(123L);
        contacts.setUser(user);
        contacts.setAddresses(Lists.newArrayList("Beijing", "Wuhan"));
        contacts.setPhones(Lists.newArrayList(new Phone(10010), new Phone(10086)));
        demoService.save(contacts);
        System.out.println("save(contacts) => void OK");

        Contacts contacts2 = new Contacts();
        contacts2.setId(124L);
        contacts2.setUser(newUser);
        contacts2.setAddresses(Lists.newArrayList("Chengdu", "Shenzhen"));
        contacts2.setPhones(Lists.newArrayList(new Phone(10011), new Phone(10087)));

        List<Contacts> contactsList = new ArrayList<>();
        contactsList.add(contacts);
        contactsList.add(contacts2);
        int size = demoService.save(contactsList);
        System.out.println("save(contactsList) => " + size);

        /* 引用 OrderService */
        System.out.println("\n--- OrderService 调用 ---");
        ReferenceConfig<OrderService> orderRef = new ReferenceConfig<>();
        orderRef.setInterface(OrderService.class);
        orderRef.setApplication("sample-consumer");
        orderRef.setModule("sample");
        orderRef.setGroup("test");
        orderRef.setVersion("2.0");
        orderRef.setCheck("false");
        orderRef.setProtocol(protocolConfig);
        orderRef.setRegistry(registryConfig);

        OrderService orderService = orderRef.getRef();

        User buyer = new User("lily", 24);
        Order order1 = orderService.createOrder(buyer, List.of("item-A", "item-B"));
        System.out.println("createOrder => " + order1);

        Order order2 = orderService.createOrder(buyer, List.of("item-C"));
        System.out.println("createOrder => " + order2);

        Order fetched = orderService.getOrder(order1.getId());
        System.out.println("getOrder => " + fetched);

        List<Order> buyerOrders = orderService.getOrdersByBuyer(buyer);
        System.out.println("getOrdersByBuyer => " + buyerOrders);

        int total = orderService.countOrders();
        System.out.println("countOrders => " + total);

        boolean cancelled = orderService.cancelOrder(order2.getId());
        System.out.println("cancelOrder => " + cancelled);

        /* 打印实际调用的服务端地址 */
        URL serverUrl2 = RpcContext.getContext().getServerUrl();
        if (serverUrl2 != null) {
            System.out.println("server => " + serverUrl2.getHost() + ":" + serverUrl2.getPort());
        }
    }

    private static ProtocolConfig createProtocolConfig(String protocolName) {
        ProtocolConfig protocolConfig = new ProtocolConfig();
        protocolConfig.setName(protocolName);
        protocolConfig.setId(protocolConfig.getName());
        protocolConfig.setEndpointFactory("jaws");
        protocolConfig.setSerialization("fastjson2");
        return protocolConfig;
    }

    private static RegistryConfig createRegistryConfig(String protocolName) {
        RegistryConfig registryConfig = new RegistryConfig();
        registryConfig.setRegProtocol(protocolName);
        registryConfig.setName("defaultRegistry");
        registryConfig.setId(registryConfig.getName());
        registryConfig.setAddress("127.0.0.1");
        registryConfig.setPort(2181);
        return registryConfig;
    }
}
