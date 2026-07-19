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
 * Injvm 协议 RPC 演示
 *
 * <pre>
 * 演示场景：
 * 1. injvm 协议 - JVM 内部调用，无需网络传输
 * 2. 多服务发布与引用 - 同时发布 DemoService 和 OrderService
 * 3. 各种参数类型 - String、POJO、List、Map、void 返回值
 * 4. 方法级别配置 - MethodConfig 设置单独超时/重试
 * 5. group/version 配置
 * </pre>
 */
public class InjvmRpcDemo {

    public static void main(String[] args) {
        System.out.println("========== Injvm RPC Demo ==========\n");

        /* 1. 发布服务 */
        exportServices();

        /* 2. 引用并调用 DemoService - 基本调用 */
        demoBasicCall();

        /* 3. 引用并调用 DemoService - 复杂参数类型 */
        demoComplexTypes();

        /* 4. 引用并调用 OrderService - 多服务演示 */
        demoOrderService();

        /* 5. 带方法级别配置的引用 */
        demoMethodConfig();

        /* 6. CompletableFuture 异步调用 */
        demoAsyncCall();

        System.out.println("\n========== Injvm RPC Demo Done ==========");
    }

    /*
     * 发布 DemoService 和 OrderService（injvm 协议）
     */
    private static void exportServices() {
        System.out.println("--- 发布服务 ---");

        // 发布 DemoService
        ServiceConfig<DemoService> demoServiceConfig = new ServiceConfig<>();
        demoServiceConfig.setRef(new DemoServiceImpl());
        demoServiceConfig.setApplication("injvm-demo-provider");
        demoServiceConfig.setInterface(DemoService.class);
        demoServiceConfig.setGroup("test");
        demoServiceConfig.setVersion("1.0");
        demoServiceConfig.setProtocol(createInjvmProtocol());
        demoServiceConfig.setRegistry(createLocalRegistry());
        demoServiceConfig.setExport(JawsConstants.PROTOCOL_INJVM + ":0");
        demoServiceConfig.export();
        System.out.println("DemoService exported.");

        // 发布 OrderService
        ServiceConfig<OrderService> orderServiceConfig = new ServiceConfig<>();
        orderServiceConfig.setRef(new OrderServiceImpl());
        orderServiceConfig.setApplication("injvm-demo-provider");
        orderServiceConfig.setInterface(OrderService.class);
        orderServiceConfig.setGroup("test");
        orderServiceConfig.setVersion("1.0");
        orderServiceConfig.setProtocol(createInjvmProtocol());
        orderServiceConfig.setRegistry(createLocalRegistry());
        orderServiceConfig.setExport(JawsConstants.PROTOCOL_INJVM + ":0");
        orderServiceConfig.export();
        System.out.println("OrderService exported.\n");
    }

    /*
     * 基本调用 - String 参数、POJO 参数
     */
    private static void demoBasicCall() {
        System.out.println("--- 基本调用 ---");

        ReferenceConfig<DemoService> ref = createReference(DemoService.class);
        DemoService demoService = ref.getRef();

        // 简单 String 调用
        String result = demoService.hello("jaws");
        System.out.println("hello('jaws') => " + result);

        // POJO 参数 + 返回值
        User user = new User("lily", 24);
        User renamed = demoService.rename(user, "lucy");
        System.out.println("rename(" + user + ", 'lucy') => " + renamed);
        System.out.println();
    }

    /*
     * 复杂参数类型 - List、Map、嵌套 POJO、void 方法
     */
    private static void demoComplexTypes() {
        System.out.println("--- 复杂参数类型 ---");

        ReferenceConfig<DemoService> ref = createReference(DemoService.class);
        DemoService demoService = ref.getRef();

        // List 返回值
        List<User> users = demoService.getUsers();
        System.out.println("getUsers() => " + users);

        // List + Map 组合
        Map<String, User> userMap = demoService.map(users);
        System.out.println("map(users) => " + userMap);

        // 嵌套 POJO（Contacts 包含 User、List<Phone>、List<String>）
        Contacts contacts = new Contacts();
        contacts.setId(1001L);
        contacts.setUser(new User("tom", 30));
        contacts.setAddresses(Lists.newArrayList("Beijing", "Shanghai"));
        contacts.setPhones(Lists.newArrayList(new Phone(10010), new Phone(10086)));
        demoService.save(contacts);
        System.out.println("save(contacts) => void OK");

        // 重载方法 - List<Contacts>
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
     * OrderService 调用 - 多服务演示
     */
    private static void demoOrderService() {
        System.out.println("--- OrderService 多服务演示 ---");

        ReferenceConfig<OrderService> ref = createReference(OrderService.class);
        OrderService orderService = ref.getRef();

        User buyer = new User("lily", 24);

        // 创建订单
        Order order1 = orderService.createOrder(buyer, List.of("item-A", "item-B"));
        System.out.println("createOrder => " + order1);

        Order order2 = orderService.createOrder(buyer, List.of("item-C"));
        System.out.println("createOrder => " + order2);

        // 查询订单
        Order fetched = orderService.getOrder(order1.getId());
        System.out.println("getOrder(" + order1.getId() + ") => " + fetched);

        // 按买家查询
        List<Order> buyerOrders = orderService.getOrdersByBuyer(buyer);
        System.out.println("getOrdersByBuyer => " + buyerOrders);

        // 订单计数
        int total = orderService.countOrders();
        System.out.println("countOrders() => " + total);

        // 取消订单
        boolean cancelled = orderService.cancelOrder(order2.getId());
        System.out.println("cancelOrder(" + order2.getId() + ") => " + cancelled);
        System.out.println();
    }

    /*
     * 方法级别配置演示 - MethodConfig
     */
    private static void demoMethodConfig() {
        System.out.println("--- MethodConfig 方法级别配置 ---");

        ReferenceConfig<DemoService> ref = new ReferenceConfig<>();
        ref.setInterface(DemoService.class);
        ref.setApplication("injvm-demo-consumer");
        ref.setGroup("test");
        ref.setVersion("1.0");
        ref.setProtocol(createInjvmProtocol());
        ref.setRegistry(createLocalRegistry());
        ref.setRequestTimeout(3000);

        // 为 hello 方法单独设置超时和重试
        MethodConfig helloMethod = new MethodConfig();
        helloMethod.setName("hello");
        helloMethod.setRequestTimeout(1000);
        helloMethod.setRetries(2);

        // 为 getUsers 方法设置不同的超时
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
     * CompletableFuture 异步调用演示
     */
    private static void demoAsyncCall() {
        System.out.println("--- CompletableFuture 异步调用 ---");

        ReferenceConfig<DemoService> ref = createReference(DemoService.class);
        DemoService demoService = ref.getRef();

        // 基本异步调用 - 返回 CompletableFuture
        CompletableFuture<String> future = demoService.helloAsync("jaws");
        future.thenAccept(result -> System.out.println("helloAsync callback => " + result));

        // 阻塞获取结果
        try {
            String syncResult = future.get();
            System.out.println("helloAsync get() => " + syncResult);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 多个异步调用组合
        CompletableFuture<String> f1 = demoService.helloAsync("world");
        CompletableFuture<String> f2 = demoService.helloAsync("jaws");
        CompletableFuture.allOf(f1, f2).thenRun(() -> {
            System.out.println("both async calls completed: " + f1.join() + ", " + f2.join());
        });
        System.out.println();
    }

    /*
     * 创建 injvm 协议的 ReferenceConfig
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
        registry.setName("localRegistry");
        registry.setId("localRegistry");
        registry.setAddress("127.0.0.1");
        registry.setPort(0);
        return registry;
    }
}
