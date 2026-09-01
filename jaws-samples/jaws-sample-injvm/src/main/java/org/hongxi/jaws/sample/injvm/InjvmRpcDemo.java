package org.hongxi.jaws.sample.injvm;

import com.google.common.collect.Lists;
import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.config.MethodConfig;
import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.ReferenceConfig;
import org.hongxi.jaws.config.RegistryConfig;
import org.hongxi.jaws.config.ServiceConfig;
import org.hongxi.jaws.sample.api.DemoService;
import org.hongxi.jaws.sample.api.OrderService;
import org.hongxi.jaws.sample.api.model.Contacts;
import org.hongxi.jaws.sample.api.model.Order;
import org.hongxi.jaws.sample.api.model.Phone;
import org.hongxi.jaws.sample.api.model.User;
import org.hongxi.jaws.sample.injvm.service.DemoServiceImpl;
import org.hongxi.jaws.sample.injvm.service.OrderServiceImpl;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Injvm protocol RPC demo
 *
 * <pre>
 * Demo scenarios:
 * 1. injvm protocol - in-JVM invocation without network transport
 * 2. Multi-service export and reference - exporting DemoService and OrderService together
 * 3. Various parameter types - String, POJO, List, Map, void return
 * 4. Method-level configuration - MethodConfig for individual timeout/retries
 * 5. group/version configuration
 * </pre>
 */
public class InjvmRpcDemo {

    public static void main(String[] args) {
        System.out.println("========== Injvm RPC Demo ==========\n");

        /* 1. Export services */
        exportServices();

        /* 2. Reference and invoke DemoService - basic calls */
        demoBasicCall();

        /* 3. Reference and invoke DemoService - complex parameter types */
        demoComplexTypes();

        /* 4. Reference and invoke OrderService - multi-service demo */
        demoOrderService();

        /* 5. Reference with method-level configuration */
        demoMethodConfig();

        /* 6. CompletableFuture async invocation */
        demoAsyncCall();

        System.out.println("\n========== Injvm RPC Demo Done ==========");
    }

    /*
     * Export DemoService and OrderService (injvm protocol)
     */
    private static void exportServices() {
        System.out.println("--- Export Services ---");

        // Export DemoService
        ServiceConfig<DemoService> demoServiceConfig = new ServiceConfig<>();
        demoServiceConfig.setRef(new DemoServiceImpl());
        demoServiceConfig.setApplication("injvm-demo-provider");
        demoServiceConfig.setInterface(DemoService.class);
        demoServiceConfig.setGroup("test");
        demoServiceConfig.setVersion("1.0");
        demoServiceConfig.setProtocol(createInjvmProtocol());
        demoServiceConfig.setRegistry(createLocalRegistry());
        demoServiceConfig.export();
        System.out.println("DemoService exported.");

        // Export OrderService
        ServiceConfig<OrderService> orderServiceConfig = new ServiceConfig<>();
        orderServiceConfig.setRef(new OrderServiceImpl());
        orderServiceConfig.setApplication("injvm-demo-provider");
        orderServiceConfig.setInterface(OrderService.class);
        orderServiceConfig.setGroup("test");
        orderServiceConfig.setVersion("1.0");
        orderServiceConfig.setProtocol(createInjvmProtocol());
        orderServiceConfig.setRegistry(createLocalRegistry());
        orderServiceConfig.export();
        System.out.println("OrderService exported.\n");
    }

    /*
     * Basic calls - String parameter, POJO parameter
     */
    private static void demoBasicCall() {
        System.out.println("--- Basic Calls ---");

        ReferenceConfig<DemoService> ref = createReference(DemoService.class);
        DemoService demoService = ref.getRef();

        // Simple String call
        String result = demoService.hello("jaws");
        System.out.println("hello('jaws') => " + result);

        // POJO parameter + return value
        User user = new User("lily", 24);
        User renamed = demoService.rename(user, "lucy");
        System.out.println("rename(" + user + ", 'lucy') => " + renamed);
        System.out.println();
    }

    /*
     * Complex parameter types - List, Map, nested POJO, void method
     */
    private static void demoComplexTypes() {
        System.out.println("--- Complex Parameter Types ---");

        ReferenceConfig<DemoService> ref = createReference(DemoService.class);
        DemoService demoService = ref.getRef();

        // List return value
        List<User> users = demoService.getUsers();
        System.out.println("getUsers() => " + users);

        // List + Map combination
        Map<String, User> userMap = demoService.map(users);
        System.out.println("map(users) => " + userMap);

        // Nested POJO (Contacts contains User, List<Phone>, List<String>)
        Contacts contacts = new Contacts();
        contacts.setId(1001L);
        contacts.setUser(new User("tom", 30));
        contacts.setAddresses(Lists.newArrayList("Beijing", "Shanghai"));
        contacts.setPhones(Lists.newArrayList(new Phone(10010), new Phone(10086)));
        demoService.save(contacts);
        System.out.println("save(contacts) => void OK");

        // Overloaded method - List<Contacts>
        Contacts contacts2 = new Contacts();
        contacts2.setId(1002L);
        contacts2.setUser(new User("jerry", 28));
        contacts2.setAddresses(Lists.newArrayList("Guangzhou"));
        contacts2.setPhones(Lists.newArrayList(new Phone(10011)));
        int count = demoService.save(List.of(contacts, contacts2));
        System.out.println("save(contactsList) => " + count);
        System.out.println();
    }

    /*
     * OrderService calls - multi-service demo
     */
    private static void demoOrderService() {
        System.out.println("--- OrderService Multi-Service Demo ---");

        ReferenceConfig<OrderService> ref = createReference(OrderService.class);
        OrderService orderService = ref.getRef();

        User buyer = new User("lily", 24);

        // Create orders
        Order order1 = orderService.createOrder(buyer, List.of("item-A", "item-B"));
        System.out.println("createOrder => " + order1);

        Order order2 = orderService.createOrder(buyer, List.of("item-C"));
        System.out.println("createOrder => " + order2);

        // Query order by id
        Order fetched = orderService.getOrder(order1.getId());
        System.out.println("getOrder(" + order1.getId() + ") => " + fetched);

        // Query orders by buyer
        List<Order> buyerOrders = orderService.getOrdersByBuyer(buyer);
        System.out.println("getOrdersByBuyer => " + buyerOrders);

        // Count orders
        int total = orderService.countOrders();
        System.out.println("countOrders() => " + total);

        // Cancel order
        boolean canceled = orderService.cancelOrder(order2.getId());
        System.out.println("cancelOrder(" + order2.getId() + ") => " + canceled);
        System.out.println();
    }

    /*
     * Method-level configuration demo - MethodConfig
     */
    private static void demoMethodConfig() {
        System.out.println("--- MethodConfig Method-Level Configuration ---");

        ReferenceConfig<DemoService> ref = new ReferenceConfig<>();
        ref.setInterface(DemoService.class);
        ref.setApplication("injvm-demo-consumer");
        ref.setGroup("test");
        ref.setVersion("1.0");
        ref.setProtocol(createInjvmProtocol());
        ref.setRegistry(createLocalRegistry());
        ref.setRequestTimeout(3000);

        // Set individual timeout and retries for the hello method
        MethodConfig helloMethod = new MethodConfig();
        helloMethod.setName("hello");
        helloMethod.setRequestTimeout(1000);
        helloMethod.setRetries(2);

        // Set a different timeout for the getUsers method
        MethodConfig getUsersMethod = new MethodConfig();
        getUsersMethod.setName("getUsers");
        getUsersMethod.setRequestTimeout(5000);

        ref.setMethods(List.of(helloMethod, getUsersMethod));

        DemoService demoService = ref.getRef();
        String result = demoService.hello("method-config-test");
        System.out.println("hello with MethodConfig => " + result);

        List<User> users = demoService.getUsers();
        System.out.println("getUsers with MethodConfig => " + users);
        System.out.println();
    }

    /*
     * CompletableFuture async invocation demo
     */
    private static void demoAsyncCall() {
        System.out.println("--- CompletableFuture Async Invocation ---");

        ReferenceConfig<DemoService> ref = createReference(DemoService.class);
        DemoService demoService = ref.getRef();

        // Basic async call - returns CompletableFuture
        CompletableFuture<String> future = demoService.helloAsync("jaws");
        future.thenAccept(result -> System.out.println("helloAsync callback => " + result));

        // Block to get the result
        try {
            String syncResult = future.get();
            System.out.println("helloAsync get() => " + syncResult);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Compose multiple async calls
        CompletableFuture<String> f1 = demoService.helloAsync("world");
        CompletableFuture<String> f2 = demoService.helloAsync("jaws");
        CompletableFuture.allOf(f1, f2).thenRun(() -> {
            System.out.println("both async calls completed: " + f1.join() + ", " + f2.join());
        });
        System.out.println();
    }

    /*
     * Create a ReferenceConfig for the injvm protocol
     */
    private static <T> ReferenceConfig<T> createReference(Class<T> interfaceClass) {
        ReferenceConfig<T> ref = new ReferenceConfig<>();
        ref.setInterface(interfaceClass);
        ref.setApplication("injvm-demo-consumer");
        ref.setGroup("test");
        ref.setVersion("1.0");
        ref.setProtocol(createInjvmProtocol());
        ref.setRegistry(createLocalRegistry());
        return ref;
    }

    private static ProtocolConfig createInjvmProtocol() {
        ProtocolConfig protocol = new ProtocolConfig();
        protocol.setName(JawsConstants.PROTOCOL_INJVM);
        protocol.setId(JawsConstants.PROTOCOL_INJVM);
        return protocol;
    }

    private static RegistryConfig createLocalRegistry() {
        RegistryConfig registry = new RegistryConfig();
        registry.setProtocol(JawsConstants.REGISTRY_PROTOCOL_LOCAL);
        registry.setId("localRegistry");
        registry.setAddress("127.0.0.1");
        registry.setPort(0);
        return registry;
    }
}
