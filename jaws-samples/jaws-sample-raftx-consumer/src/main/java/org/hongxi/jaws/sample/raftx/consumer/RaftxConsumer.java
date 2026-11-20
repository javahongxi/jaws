package org.hongxi.jaws.sample.raftx.consumer;

import com.google.common.collect.Lists;
import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.ReferenceConfig;
import org.hongxi.jaws.config.RegistryConfig;
import org.hongxi.jaws.rpc.RpcContext;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.sample.api.DemoService;
import org.hongxi.jaws.sample.api.model.Contacts;
import org.hongxi.jaws.sample.api.model.Phone;
import org.hongxi.jaws.sample.api.model.User;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Consumer for the persistent-instance demo through the **nacos leg**:
 * discovers the DemoService registered with {@code ephemeral=false} by
 * {@code RaftxProvider} (real nacos-client) or {@code RaftProvider} (native
 * HarborClient), subscribing through {@code jaws-registry-nacos} against
 * HarborServer.
 *
 * <p>The group is "persistent" — the same coordinate both raft providers
 * publish. Discovery is an ordinary naming read, but the subscription itself
 * is the interop check: harbor must push persistent-service changes (including
 * Raft-replicated health flips) to a real nacos-client subscriber.</p>
 *
 * <p>Start harbor (./run-sample.sh harbor-standalone) and RaftxProvider or
 * RaftProvider first — discovering a provider registered by the other leg is
 * the point of this pair.</p>
 *
 * @author shenhongxi
 */
public class RaftxConsumer {

    private static final int HARBOR_PORT = Integer.parseInt(System.getProperty("harbor.port", "19848"));
    private static final String GROUP = "persistent";

    public static void main(String[] args) {
        // nacos-client reads the gRPC port as HTTP port + offset; HarborServer listens
        // on one port, so the offset must be zeroed for this leg.
        System.setProperty("nacos.server.grpc.port.offset", "0");
        ProtocolConfig protocolConfig = new ProtocolConfig();
        protocolConfig.setName(JawsConstants.PROTOCOL_JAWS);
        protocolConfig.setId(protocolConfig.getName());
        protocolConfig.setTransportFactory("netty");
        protocolConfig.setSerialization("fastjson2");

        RegistryConfig registryConfig = new RegistryConfig();
        registryConfig.setProtocol(JawsConstants.REGISTRY_PROTOCOL_NACOS);
        registryConfig.setId("defaultRegistry");
        registryConfig.setAddress("127.0.0.1:" + HARBOR_PORT);
        registryConfig.setPort(HARBOR_PORT);

        ReferenceConfig<DemoService> demoRef = new ReferenceConfig<>();
        demoRef.setInterface(DemoService.class);
        demoRef.setApplication("sample-raftx-consumer");
        demoRef.setModule("sample-raftx");
        demoRef.setGroup(GROUP);
        demoRef.setRequestTimeout(2000);
        demoRef.setVersion("2.0");
        demoRef.setCheck(false);
        demoRef.setProtocol(protocolConfig);
        demoRef.setRegistry(registryConfig);

        DemoService demoService = demoRef.getRef();

        /* Basic calls */
        System.out.println("--- DemoService Basic Calls (persistent instance, nacos leg) ---");
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

        /* Sample calls done — destroy the reference for clean shutdown.
           This ensures the Nacos client's internal connections are closed
           gracefully, avoiding any stray GOAWAY frames during JVM exit. */
        demoRef.destroy();
    }
}