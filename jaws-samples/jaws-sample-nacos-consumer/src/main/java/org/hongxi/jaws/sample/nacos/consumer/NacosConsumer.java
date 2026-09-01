package org.hongxi.jaws.sample.nacos.consumer;

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
 * Service consumer sample
 *
 * <pre>
 * Demo scenarios:
 * 1. jaws protocol + Nacos registry
 * 2. Multi-service reference - DemoService + OrderService
 * 3. Calls with various parameter types - String, POJO, List, Map, nested objects
 * 4. group/version configuration
 * </pre>
 *
 * Run NacosProvider first to make sure the services are exported
 */
public class NacosConsumer {

    public static void main(String[] args) throws Exception {
        ProtocolConfig protocolConfig = createProtocolConfig(JawsConstants.PROTOCOL_JAWS);
        RegistryConfig registryConfig = createRegistryConfig(JawsConstants.REGISTRY_PROTOCOL_NACOS);

        /* Reference DemoService */
        ReferenceConfig<DemoService> demoRef = new ReferenceConfig<>();
        demoRef.setInterface(DemoService.class);
        demoRef.setApplication("sample-nacos-consumer");
        demoRef.setModule("sample-nacos");
        demoRef.setGroup("test");
        demoRef.setRequestTimeout(2000);
        demoRef.setVersion("2.0");
        demoRef.setCheck(false);
        demoRef.setProtocol(protocolConfig);
        demoRef.setRegistry(registryConfig);

        DemoService demoService = demoRef.getRef();

        /* Basic calls */
        System.out.println("--- DemoService Basic Calls ---");
        String r = demoService.hello("lily");
        System.out.println("hello => " + r);

        /* Print the actual server address being invoked */
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

        /* Reference OrderService */
        System.out.println("\n--- OrderService Calls ---");
        ReferenceConfig<OrderService> orderRef = new ReferenceConfig<>();
        orderRef.setInterface(OrderService.class);
        orderRef.setApplication("sample-nacos-consumer");
        orderRef.setModule("sample-nacos");
        orderRef.setGroup("test");
        orderRef.setVersion("2.0");
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

        /* Sample calls done, force exit (non-daemon threads of Netty/Curator would prevent JVM auto-exit) */
        System.exit(0);
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
        registryConfig.setPort(8848);
        return registryConfig;
    }
}
