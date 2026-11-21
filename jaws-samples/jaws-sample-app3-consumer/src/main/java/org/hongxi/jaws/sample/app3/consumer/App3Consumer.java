package org.hongxi.jaws.sample.app3.consumer;

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
 * Application-level service discovery consumer sample on the ZooKeeper registry
 * leg. The reference configuration is completely ordinary — there is NO
 * consumer-side switch: because App3Provider wrote the interface-to-application
 * mappings into ZooKeeper's DynamicConfiguration, these references are
 * automatically routed to {@code AppLevelRegistryDirectory}.
 *
 * <pre>
 * Demo scenarios:
 * 1. Mapping-driven routing — "mapping:{interface}" exists -> subscribe to the
 *    application instance (path = application name) instead of interface URLs
 * 2. Metadata pull chain — watch the logs: "Fetched metadata from ..." means a
 *    MetadataService RPC, "Metadata cache hit" means the shared revision cache
 *    short-circuited it (the second interface hits the cache)
 * 3. Calls are identical to the interface-level zookeeper sample
 * </pre>
 *
 * <p>Make sure ZooKeeper is running at 127.0.0.1:2181 and start App3Provider
 * first. See doc/app-level-discovery.md.</p>
 */
public class App3Consumer {

    public static void main(String[] args) {
        ProtocolConfig protocolConfig = createProtocolConfig(JawsConstants.PROTOCOL_JAWS);
        RegistryConfig registryConfig = createRegistryConfig(JawsConstants.REGISTRY_PROTOCOL_ZOOKEEPER);

        /* Reference DemoService — plain configuration, app-level discovery is
           selected automatically through the interface-to-application mapping. */
        ReferenceConfig<DemoService> demoRef = new ReferenceConfig<>();
        demoRef.setInterface(DemoService.class);
        demoRef.setApplication("sample-app3-consumer");
        demoRef.setModule("sample-app3");
        demoRef.setRequestTimeout(2000);
        demoRef.setCheck(false);
        demoRef.setProtocol(protocolConfig);
        demoRef.setRegistry(registryConfig);

        DemoService demoService = demoRef.getRef();

        /* Basic calls */
        System.out.println("--- DemoService Basic Calls ---");
        String r = demoService.hello("lily");
        System.out.println("hello => " + r);

        URL serverUrl = RpcContext.getContext().getServerUrl();
        if (serverUrl != null) {
            System.out.println("server => " + serverUrl.getHost() + ":" + serverUrl.getPort());
        }

        User user = new User("lily", 24);
        User newUser = demoService.rename(user, "lucy");
        System.out.println("rename => " + newUser);

        /* Complex parameter calls */
        System.out.println("\n--- DemoService Complex Parameters ---");
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

        /* Reference OrderService — same application, same revision: the
           metadata pull is short-circuited by the shared revision cache. */
        System.out.println("\n--- OrderService Calls ---");
        ReferenceConfig<OrderService> orderRef = new ReferenceConfig<>();
        orderRef.setInterface(OrderService.class);
        orderRef.setApplication("sample-app3-consumer");
        orderRef.setModule("sample-app3");
        orderRef.setCheck(false);
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

        boolean canceled = orderService.cancelOrder(order2.getId());
        System.out.println("cancelOrder => " + canceled);

        /* Print the actual server address being invoked */
        URL serverUrl2 = RpcContext.getContext().getServerUrl();
        if (serverUrl2 != null) {
            System.out.println("server => " + serverUrl2.getHost() + ":" + serverUrl2.getPort());
        }

        /* Sample calls done — destroy references for clean shutdown. */
        orderRef.destroy();
        demoRef.destroy();
    }

    private static ProtocolConfig createProtocolConfig(String protocolName) {
        ProtocolConfig protocolConfig = new ProtocolConfig();
        protocolConfig.setName(protocolName);
        protocolConfig.setId(protocolConfig.getName());
        protocolConfig.setTransportFactory("netty");
        protocolConfig.setSerialization("fastjson2");
        return protocolConfig;
    }

    private static RegistryConfig createRegistryConfig(String protocolName) {
        RegistryConfig registryConfig = new RegistryConfig();
        registryConfig.setProtocol(protocolName);
        registryConfig.setId("defaultRegistry");
        registryConfig.setAddress("127.0.0.1");
        registryConfig.setPort(2181);
        return registryConfig;
    }
}