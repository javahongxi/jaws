package org.hongxi.jaws.registry.zookeeper;

import org.I0Itec.zkclient.ZkClient;
import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.registry.support.command.CommandListener;
import org.hongxi.jaws.registry.support.command.ServiceListener;
import org.hongxi.jaws.rpc.URL;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Properties;

import static org.junit.Assert.*;

/**
 * Created by shenhongxi on 2021/4/24.
 */
public class ZookeeperRegistryTest {
    private static ZookeeperRegistry registry;
    private static URL serviceUrl, clientUrl;
    private static EmbeddedZookeeper zookeeper;
    private static ZkClient zkClient;
    private static String service = "org.hongxi.jaws.demoService";

    @BeforeClass
    public static void setUp() throws Exception {
        Properties properties = new Properties();
        InputStream in = EmbeddedZookeeper.class.getResourceAsStream("/zoo.cfg");
        properties.load(in);
        int port = Integer.parseInt(properties.getProperty("clientPort"));
        in.close();

        URL zkUrl = new URL("zookeeper", "127.0.0.1", port, "org.hongxi.jaws.registry.RegistryService");
        clientUrl = new URL(JawsConstants.PROTOCOL_JAWS, "127.0.0.1", 0, service);
        clientUrl.addParameter("group", "aaa");

        serviceUrl = new URL(JawsConstants.PROTOCOL_JAWS, "127.0.0.1", 8001, service);
        serviceUrl.addParameter("group", "aaa");

        zookeeper = new EmbeddedZookeeper();
        zookeeper.start();
        Thread.sleep(1000);
        zkClient = new ZkClient("127.0.0.1:" + port, 5000);
        registry = new ZookeeperRegistry(zkUrl, zkClient);
    }

    @After
    public void tearDown() {
        zkClient.deleteRecursive(JawsConstants.ZOOKEEPER_REGISTRY_NAMESPACE);
    }

    @Test
    public void subAndUnsubService() throws Exception {
        ServiceListener serviceListener = (refUrl, registryUrl, urls) -> {
            if (!urls.isEmpty()) {
                assertTrue(urls.contains(serviceUrl));
            }
        };
        registry.subscribeService(clientUrl, serviceListener);
        assertTrue(containsServiceListener(clientUrl, serviceListener));
        registry.doRegister(serviceUrl);
        registry.doAvailable(serviceUrl);
        Thread.sleep(2000);

        registry.unsubscribeService(clientUrl, serviceListener);
        assertFalse(containsServiceListener(clientUrl, serviceListener));
    }

    private boolean containsServiceListener(URL clientUrl, ServiceListener serviceListener) {
        return registry.getServiceListeners().get(clientUrl).containsKey(serviceListener);
    }

    @Test
    public void subAndUnsubCommand() throws Exception {
        final String command = "{\"index\":0,\"mergeGroups\":[\"aaa:1\",\"bbb:1\"],\"pattern\":\"*\",\"routeRules\":[]}\n";
        CommandListener commandListener = (refUrl, commandString) -> {
            if (commandString != null) {
                assertEquals(command, commandString);
            }
        };
        registry.subscribeCommand(clientUrl, commandListener);
        assertTrue(containsCommandListener(clientUrl, commandListener));

        String commandPath = ZkUtils.toCommandPath(clientUrl);
        if (!zkClient.exists(commandPath)) {
            zkClient.createPersistent(commandPath, true);
        }
        zkClient.writeData(commandPath, command);
        Thread.sleep(2000);

        zkClient.delete(commandPath);

        registry.unsubscribeCommand(clientUrl, commandListener);
        assertFalse(containsCommandListener(clientUrl, commandListener));
    }

    private boolean containsCommandListener(URL clientUrl, CommandListener commandListener) {
        return registry.getCommandListeners().get(clientUrl).containsKey(commandListener);
    }

    @Test
    public void discoverService() throws Exception {
        registry.doRegister(serviceUrl);
        List<URL> results = registry.discoverService(clientUrl);
        assertTrue(results.isEmpty());

        registry.doAvailable(serviceUrl);
        results = registry.discoverService(clientUrl);
        assertTrue(results.contains(serviceUrl));
    }

    @Test
    public void discoverCommand() throws Exception {
        String result = registry.discoverCommand(clientUrl);
        assertEquals("", result);

        String command = "{\"index\":0,\"mergeGroups\":[\"aaa:1\",\"bbb:1\"],\"pattern\":\"*\",\"routeRules\":[]}\n";
        String commandPath = ZkUtils.toCommandPath(clientUrl);
        if (!zkClient.exists(commandPath)) {
            zkClient.createPersistent(commandPath, true);
        }
        zkClient.writeData(commandPath, command);
        result = registry.discoverCommand(clientUrl);
        assertEquals(result, command);
    }

    @Test
    public void doRegisterAndAvailable() throws Exception {
        String node = serviceUrl.getServerPortStr();
        List<String> available, unavailable;
        String unavailablePath = ZkUtils.toNodeTypePath(serviceUrl, ZkNodeType.UNAVAILABLE_SERVER);
        String availablePath = ZkUtils.toNodeTypePath(serviceUrl, ZkNodeType.AVAILABLE_SERVER);

        registry.doRegister(serviceUrl);
        unavailable = zkClient.getChildren(unavailablePath);
        assertTrue(unavailable.contains(node));

        registry.doAvailable(serviceUrl);
        unavailable = zkClient.getChildren(unavailablePath);
        assertFalse(unavailable.contains(node));
        available = zkClient.getChildren(availablePath);
        assertTrue(available.contains(node));

        registry.doUnavailable(serviceUrl);
        unavailable = zkClient.getChildren(unavailablePath);
        assertTrue(unavailable.contains(node));
        available = zkClient.getChildren(availablePath);
        assertFalse(available.contains(node));

        registry.doUnregister(serviceUrl);
        unavailable = zkClient.getChildren(unavailablePath);
        assertFalse(unavailable.contains(node));
        available = zkClient.getChildren(availablePath);
        assertFalse(available.contains(node));
    }

}