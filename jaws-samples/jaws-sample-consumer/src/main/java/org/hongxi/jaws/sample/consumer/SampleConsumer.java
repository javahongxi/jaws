package org.hongxi.jaws.sample.consumer;

import com.google.common.collect.Lists;
import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.RefererConfig;
import org.hongxi.jaws.config.RegistryConfig;
import org.hongxi.jaws.sample.api.DemoService;
import org.hongxi.jaws.sample.api.model.Contacts;
import org.hongxi.jaws.sample.api.model.Phone;
import org.hongxi.jaws.sample.api.model.User;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Created by shenhongxi on 2021/4/25.
 */
public class SampleConsumer {

    public static void main(String[] args) {
        RefererConfig<DemoService> refererConfig = new RefererConfig<>();
        refererConfig.setInterface(DemoService.class);
        refererConfig.setApplication("sample-consumer");
        refererConfig.setModule("sample");
        refererConfig.setGroup("test");
        refererConfig.setRequestTimeout(2000);
        refererConfig.setVersion("2.0");
        refererConfig.setCheck("false");
        refererConfig.setProtocol(createProtocolConfig(JawsConstants.PROTOCOL_JAWS));
        refererConfig.setRegistry(createRegistryConfig(JawsConstants.REGISTRY_PROTOCOL_ZOOKEEPER));

        DemoService demoService = refererConfig.getRef();
        String r = demoService.hello("lily");
        System.out.println(r);

        User user = new User("lily", 24);
        User newUser = demoService.rename(user, "lucy");
        System.out.println(newUser);

        List<User> users = demoService.getUsers();
        System.out.println(users);

        Map<String, User> map = demoService.map(users);
        System.out.println(map);

        Contacts contacts = new Contacts();
        contacts.setId(123L);
        contacts.setUser(user);
        contacts.setAddresses(Lists.newArrayList("Beijing", "Wuhan"));
        contacts.setPhones(Lists.newArrayList(new Phone(10010), new Phone(10086)));
        demoService.save(contacts);

        Contacts contacts2 = new Contacts();
        contacts2.setId(124L);
        contacts2.setUser(newUser);
        contacts2.setAddresses(Lists.newArrayList("Chengdu", "Shenzhen"));
        contacts2.setPhones(Lists.newArrayList(new Phone(10011), new Phone(10087)));

        List<Contacts> contactsList = new ArrayList<>();
        contactsList.add(contacts);
        contactsList.add(contacts2);
        int size = demoService.save(contactsList);
        System.out.println(size);
    }

    protected static ProtocolConfig createProtocolConfig(String protocolName) {
        ProtocolConfig protocolConfig = new ProtocolConfig();
        protocolConfig.setName(protocolName);
        protocolConfig.setId(protocolConfig.getName());
        protocolConfig.setEndpointFactory("jaws");
        return protocolConfig;
    }

    protected static RegistryConfig createRegistryConfig(String protocolName) {
        RegistryConfig registryConfig = new RegistryConfig();
        registryConfig.setRegProtocol(protocolName);
        registryConfig.setName("defaultRegistry");
        registryConfig.setId(registryConfig.getName());
        registryConfig.setAddress("127.0.0.1");
        registryConfig.setPort(2181);
        return registryConfig;
    }
}
