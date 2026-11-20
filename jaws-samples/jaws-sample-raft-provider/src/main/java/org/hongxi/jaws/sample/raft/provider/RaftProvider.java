package org.hongxi.jaws.sample.raft.provider;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.ServiceConfig;
import org.hongxi.jaws.harbor.client.HarborClient;
import org.hongxi.jaws.harbor.client.HarborClientConfig;
import org.hongxi.jaws.harbor.model.Instance;
import org.hongxi.jaws.sample.api.DemoService;
import org.hongxi.jaws.sample.raft.provider.service.DemoServiceImpl;

import java.util.Map;

/**
 * Persistent-instance demo: one {@code DemoService} provider registered with
 * {@code ephemeral=false}, so its registration lives in harbor's Raft log
 * instead of hanging off this process's connection.
 *
 * <pre>
 * How to verify durability:
 * 1. Start harbor:  HarborBootstrap
 *    (Raft data defaults to ~/.harbor/data; override with --data-dir <dir>)
 * 2. Run this provider, then check the CP state:
 *      curl http://127.0.0.1:19858/api/raft          (role/term/commitIndex)
 *    Consume it with RaftConsumer (jaws-sample-raft-consumer).
 * 3. kill -9 this provider: an ephemeral registration would vanish with the
 *    connection, but this one stays. The leader's TCP probe notices the dead
 *    address and flips healthy=false through a HEALTH command within seconds.
 * 4. kill -9 harbor itself and restart it with the SAME data directory
 *    (the default ~/.harbor/data needs no flag): the registration is
 *    recovered from the Raft log/snapshot on disk.
 * 5. Run this provider again: re-registration is an idempotent replace, and
 *    the probe flips healthy back to true once the port answers.
 * </pre>
 *
 * <p>The group is "persistent" on purpose: harbor refuses to mix ephemeral and
 * persistent publishers under one service, and the harbor sample's HarborProvider
 * already publishes the same interface ephemerally under group "test".</p>
 *
 * @author shenhongxi
 */
public class RaftProvider {

    private static final int PORT = Integer.parseInt(System.getProperty("port", "20010"));
    private static final int HARBOR_PORT = Integer.parseInt(System.getProperty("harbor.port", "19848"));
    private static final String GROUP = "persistent";

    public static void main(String[] args) {
        /* Export the RPC server only — no registry leg, so nothing ephemeral is registered. */
        ProtocolConfig protocolConfig = new ProtocolConfig();
        protocolConfig.setName(JawsConstants.PROTOCOL_JAWS);
        protocolConfig.setId(protocolConfig.getName());
        protocolConfig.setTransportFactory("netty");
        protocolConfig.setSerialization("fastjson2");
        protocolConfig.setPort(PORT);

        ServiceConfig<DemoService> serviceConfig = new ServiceConfig<>();
        serviceConfig.setRef(new DemoServiceImpl());
        serviceConfig.setApplication("sample-raft-provider");
        serviceConfig.setInterface(DemoService.class);
        serviceConfig.setGroup(GROUP);
        serviceConfig.setVersion("2.0");
        serviceConfig.setProtocol(protocolConfig);
        serviceConfig.export();
        System.out.println("DemoService exported on port " + PORT + " (no ephemeral registration).");

        /* Register the same address persistently: this write commits through Raft. */
        Instance instance = new Instance();
        instance.setIp("127.0.0.1");
        instance.setPort(PORT);
        instance.setEphemeral(false);
        instance.setHealthy(true);
        instance.setEnabled(true);
        instance.setWeight(1.0);
        // The same sentinel keys HarborRegistry uses, so a jaws consumer can
        // rebuild a provider URL from this instance. endpointType=service is
        // mandatory: URL.canServe refuses any provider URL without it (the
        // ephemeral leg gets it from ServiceConfig's export parameters).
        // serialization must match the export above, or the consumer falls
        // back to its own default codec (hessian2) and the wire mismatches.
        instance.setMetadata(Map.of(
                "protocol", JawsConstants.PROTOCOL_JAWS,
                "path", DemoService.class.getName(),
                "interface", DemoService.class.getName(),
                "group", GROUP,
                "version", "2.0",
                UrlParam.Identity.ENDPOINT_TYPE.getName(), JawsConstants.ENDPOINT_TYPE_SERVICE,
                UrlParam.Transport.SERIALIZATION.getName(), "fastjson2"));

        String serviceName = JawsConstants.REGISTRY_SERVICE_NAMESPACE
                + JawsConstants.PATH_SEPARATOR + DemoService.class.getName();
        HarborClient client = new HarborClient(HarborClientConfig.of("127.0.0.1", HARBOR_PORT));
        client.registerInstance(serviceName, GROUP, instance);
        System.out.println("Persistent instance registered: " + serviceName
                + " group=" + GROUP + " -> 127.0.0.1:" + PORT);
        System.out.println("Try kill -9 on this process, or on harbor, and watch /api/raft.");
        // The client stays open (its redo replays the registration after a harbor
        // restart), and the exported RPC server keeps the JVM alive.
    }
}