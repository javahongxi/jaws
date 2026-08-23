package org.hongxi.jaws.sample.consumer.direct;

import com.google.common.collect.Lists;
import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.ReferenceConfig;
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
 * Direct-mode consumer - connects to provider via {@code directUrl}, no registry needed.
 *
 * <pre>
 * Demo scenario:
 * 1. jaws protocol + directUrl (bypasses registry discovery)
 * 2. Multi-Service reference - DemoService + OrderService
 * 3. Various parameter types - String, POJO, List, Map, nested objects
 * 4. group/version configuration
 * </pre>
 *
 * <p>Run {@code DirectProvider} first before starting this consumer.
 */
public class DirectConsumer {

    private static final String DIRECT_URL = System.getProperty("directUrl", "127.0.0.1:10000");

    public static void main(String[] args) throws Exception {
        ProtocolConfig protocolConfig = createProtocolConfig();

        /* Reference DemoService with directUrl */
        ReferenceConfig<DemoService> demoRef = new ReferenceConfig<>();
        demoRef.setInterface(DemoService.class);
        demoRef.setApplication("sample-consumer-direct");
        demoRef.setModule("sample-direct");
        demoRef.setGroup("test");
        demoRef.setVersion("2.0");
        demoRef.setRequestTimeout(2000);
        demoRef.setProtocol(protocolConfig);
        demoRef.setDirectUrl(DIRECT_URL);

        DemoService demoService = demoRef.getRef();

        /* Basic invocation */
        System.out.println("--- DemoService basic invocation ---");
        String r = demoService.hello("lily");
        System.out.println("hello => " + r);

        /* Print the actual server address */
        URL serverUrl = RpcContext.getContext().getServerUrl();
        if (serverUrl != null) {
            System.out.println("server => " + serverUrl.getHost() + ":" + serverUrl.getPort());
        }

        User user = new User("lily", 24);
        User newUser = demoService.rename(user, "lucy");
        System.out.println("rename => " + newUser);

        /* Complex parameter invocation */
        System.out.println("\n--- DemoService complex parameters ---");
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

        /* Reference OrderService with directUrl */
        System.out.println("\n--- OrderService invocation ---");
        ReferenceConfig<OrderService> orderRef = new ReferenceConfig<>();
        orderRef.setInterface(OrderService.class);
        orderRef.setApplication("sample-consumer-direct");
        orderRef.setModule("sample-direct");
        orderRef.setGroup("test");
        orderRef.setVersion("2.0");
        orderRef.setProtocol(protocolConfig);
        orderRef.setDirectUrl(DIRECT_URL);

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

        /* Print the actual server address */
        URL serverUrl2 = RpcContext.getContext().getServerUrl();
        if (serverUrl2 != null) {
            System.out.println("server => " + serverUrl2.getHost() + ":" + serverUrl2.getPort());
        }

        /* Exit forcibly (Netty non-daemon threads would prevent JVM from exiting) */
        System.exit(0);
    }

    private static ProtocolConfig createProtocolConfig() {
        ProtocolConfig protocolConfig = new ProtocolConfig();
        protocolConfig.setName(JawsConstants.PROTOCOL_JAWS);
        protocolConfig.setId(protocolConfig.getName());
        protocolConfig.setTransportFactory("netty");
        protocolConfig.setSerialization("fastjson2");
        return protocolConfig;
    }
}
