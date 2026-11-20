package org.hongxi.jaws.sample.raftx.provider;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.naming.NamingFactory;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.ServiceConfig;
import org.hongxi.jaws.sample.api.DemoService;
import org.hongxi.jaws.sample.raftx.provider.service.DemoServiceImpl;

import java.util.Map;
import java.util.Properties;

/**
 * Persistent-instance demo through the **nacos leg**: a real nacos-client
 * {@link NamingService} registers DemoService with {@code ephemeral=false}
 * against HarborServer, so the write travels the Nacos-compatible
 * {@code PersistentInstanceRequest} path and commits through harbor's Raft log.
 * It is the interop half of the raft samples — the same CP path RaftProvider
 * exercises with the native HarborClient, seen from the SDK harbor claims
 * compatibility with.
 *
 * <pre>
 * How to run:
 * 1. Start harbor:  ./run-sample.sh harbor-standalone   (or harbor-cluster)
 * 2. Run this provider, then check the CP state:
 *      curl http://127.0.0.1:19858/api/raft          (role/term/commitIndex)
 * 3. Consume it with RaftxConsumer (nacos leg) or RaftConsumer (native leg) —
 *    discovering a provider registered by the other leg is the point.
 * 4. kill -9 this provider: the registration stays in the Raft log; the
 *    leader's TCP probe flips healthy=false through a HEALTH command.
 * </pre>
 *
 * <p>The registration goes through {@code NamingService} directly because
 * {@code NacosRegistry} always registers ephemeral instances. nacos-client
 * picks the gRPC persistent path only when harbor's setup ack declares
 * {@code supportPersistentInstanceByGrpc=true}; otherwise it would fall back
 * to the HTTP v1 API, which harbor does not serve.</p>
 *
 * <p>The group is "persistent" on purpose: harbor refuses to mix ephemeral and
 * persistent publishers under one service, and the harbor samples already
 * publish the same interface ephemerally under group "test".</p>
 *
 * @author shenhongxi
 */
public class RaftxProvider {

    private static final int PORT = Integer.parseInt(System.getProperty("port", "20011"));
    private static final int HARBOR_PORT = Integer.parseInt(System.getProperty("harbor.port", "19848"));
    private static final String GROUP = "persistent";

    public static void main(String[] args) throws Exception {
        // nacos-client reads the gRPC port as HTTP port + offset; HarborServer listens
        // on one port, so the offset must be zeroed for this leg.
        System.setProperty("nacos.server.grpc.port.offset", "0");

        /* Export the RPC server only — no registry leg, so nothing ephemeral is registered. */
        ProtocolConfig protocolConfig = new ProtocolConfig();
        protocolConfig.setName(JawsConstants.PROTOCOL_JAWS);
        protocolConfig.setId(protocolConfig.getName());
        protocolConfig.setTransportFactory("netty");
        protocolConfig.setSerialization("fastjson2");
        protocolConfig.setPort(PORT);

        ServiceConfig<DemoService> serviceConfig = new ServiceConfig<>();
        serviceConfig.setRef(new DemoServiceImpl());
        serviceConfig.setApplication("sample-raftx-provider");
        serviceConfig.setInterface(DemoService.class);
        serviceConfig.setGroup(GROUP);
        serviceConfig.setVersion("2.0");
        serviceConfig.setProtocol(protocolConfig);
        serviceConfig.export();
        System.out.println("DemoService exported on port " + PORT + " (no ephemeral registration).");

        /* Register the same address persistently through the real nacos-client:
           ephemeral=false routes the write to PersistentInstanceRequest, which
           harbor commits through Raft. */
        Properties properties = new Properties();
        properties.setProperty(PropertyKeyConst.SERVER_ADDR, "127.0.0.1:" + HARBOR_PORT);
        NamingService namingService = NamingFactory.createNamingService(properties);

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
        namingService.registerInstance(serviceName, GROUP, instance);
        System.out.println("Persistent instance registered: " + serviceName
                + " group=" + GROUP + " -> 127.0.0.1:" + PORT);
        System.out.println("Try kill -9 on this process, or on harbor, and watch /api/raft.");
        // The exported RPC server keeps the JVM alive; the registration itself
        // lives in harbor's Raft log, independent of this process's connection.
    }
}