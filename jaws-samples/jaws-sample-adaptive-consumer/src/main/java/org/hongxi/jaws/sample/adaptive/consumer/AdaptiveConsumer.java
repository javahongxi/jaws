package org.hongxi.jaws.sample.adaptive.consumer;

import com.google.common.collect.Lists;
import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.ReferenceConfig;
import org.hongxi.jaws.rpc.RpcContext;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.sample.api.DemoService;
import org.hongxi.jaws.sample.api.model.Contacts;
import org.hongxi.jaws.sample.api.model.Phone;
import org.hongxi.jaws.sample.api.model.User;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Adaptive transport consumer — connects to the adaptive provider via jaws binary
 * protocol ({@code transportFactory=netty}).
 *
 * <pre>
 * The adaptive provider speaks three protocols on a single port; this consumer
 * uses the jaws binary protocol (best performance) to exercise DemoService,
 * then re-invokes DemoService over HTTP/2 transport to verify multi-transport
 * connectivity. HTTP/1.1 access can be verified separately via curl
 * (see AdaptiveProvider javadoc for curl commands).
 * </pre>
 *
 * <p>Run {@code AdaptiveProvider} first before starting this consumer.
 */
public class AdaptiveConsumer {

    private static final String DIRECT_URL = System.getProperty("directUrl", "127.0.0.1:10000");
    private static final String SERIALIZATION = System.getProperty("serialization", "fastjson2");

    public static void main(String[] args) {
        /* Reference DemoService with directUrl via netty transport */
        System.out.println("--- DemoService invocation (netty transport) ---");
        ReferenceConfig<DemoService> demoRef = new ReferenceConfig<>();
        demoRef.setInterface(DemoService.class);
        demoRef.setApplication("sample-adaptive-consumer");
        demoRef.setModule("sample-adaptive");
        demoRef.setGroup("test");
        demoRef.setVersion("2.0");
        demoRef.setRequestTimeout(2000);
        demoRef.setProtocol(createProtocolConfig("netty"));
        demoRef.setDirectUrl(DIRECT_URL);

        DemoService demoService = demoRef.getRef();

        /* Basic invocation */
        System.out.println("--- DemoService basic invocation ---");
        String r = demoService.hello("lily");
        System.out.println("hello => " + r);

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

        /* Async invocation */
        System.out.println("\n--- DemoService async invocation ---");
        CompletableFuture<String> asyncHello = demoService.helloAsync("async-lily");
        System.out.println("helloAsync submitted, thread=" + Thread.currentThread().getName());
        asyncHello.thenAccept(result -> System.out.println("helloAsync callback => " + result
                + ", thread=" + Thread.currentThread().getName()));

        CompletableFuture<User> asyncUser = demoService.getUserAsync("async-user");
        asyncUser.thenAccept(u -> System.out.println("getUserAsync callback => " + u));

        asyncHello.join();
        asyncUser.join();

        /* Reference DemoService with directUrl via HTTP/2 transport */
        System.out.println("\n--- DemoService invocation (HTTP/2 transport) ---");
        ReferenceConfig<DemoService> http2Ref = new ReferenceConfig<>();
        http2Ref.setInterface(DemoService.class);
        http2Ref.setApplication("sample-adaptive-consumer");
        http2Ref.setModule("sample-adaptive");
        http2Ref.setGroup("test");
        http2Ref.setVersion("2.0");
        http2Ref.setProtocol(createProtocolConfig("http2"));
        http2Ref.setDirectUrl(DIRECT_URL);

        DemoService http2Demo = http2Ref.getRef();

        String r2 = http2Demo.hello("lily");
        System.out.println("hello => " + r2);

        User user2 = new User("lily", 24);
        User renamed2 = http2Demo.rename(user2, "lucy");
        System.out.println("rename => " + renamed2);

        List<User> users2 = http2Demo.getUsers();
        System.out.println("getUsers => " + users2);

        Map<String, User> map2 = http2Demo.map(users2);
        System.out.println("map => " + map2);

        URL serverUrl2 = RpcContext.getContext().getServerUrl();
        if (serverUrl2 != null) {
            System.out.println("server => " + serverUrl2.getHost() + ":" + serverUrl2.getPort());
        }

        /* HTTP/1.1 verification via JDK HttpClient */
        System.out.println("\n--- HTTP/1.1 verification ---");
        try {
            HttpClient httpClient = HttpClient.newHttpClient();
            String baseUrl = "http://" + DIRECT_URL;

            // Health check
            HttpResponse<String> healthResp = httpClient.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            System.out.println("GET /health => " + healthResp.statusCode() + " " + healthResp.body());

            // RPC invoke
            String invokeJson = """
                    {"interface":"org.hongxi.jaws.sample.api.DemoService","method":"hello","group":"test","version":"2.0","args":["lily"]}
                    """;
            HttpResponse<String> invokeResp = httpClient.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/invoke"))
                            .header("content-type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(invokeJson))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            System.out.println("POST /invoke => " + invokeResp.statusCode() + " " + invokeResp.body());
        } catch (Exception e) {
            System.err.println("HTTP/1.1 verification failed: " + e.getMessage());
        }

        /* Exit forcibly (Netty non-daemon threads would prevent JVM from exiting) */
        System.exit(0);
    }

    private static ProtocolConfig createProtocolConfig(String transport) {
        ProtocolConfig protocolConfig = new ProtocolConfig();
        protocolConfig.setName(JawsConstants.PROTOCOL_JAWS);
        protocolConfig.setId(protocolConfig.getName());
        protocolConfig.setTransportFactory(transport);
        protocolConfig.setSerialization(SERIALIZATION);
        return protocolConfig;
    }
}
