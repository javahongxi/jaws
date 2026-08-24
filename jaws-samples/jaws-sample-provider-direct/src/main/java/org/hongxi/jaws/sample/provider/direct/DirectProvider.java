package org.hongxi.jaws.sample.provider.direct;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.ServiceConfig;
import org.hongxi.jaws.sample.api.DemoService;
import org.hongxi.jaws.sample.api.OrderService;
import org.hongxi.jaws.sample.provider.direct.service.DemoServiceImpl;
import org.hongxi.jaws.sample.provider.direct.service.OrderServiceImpl;

/**
 * Direct-mode provider - no registry dependency.
 *
 * <pre>
 * Demo scenario:
 * 1. jaws protocol, no registry (export only, skip registration)
 * 2. Multi-Service publishing - DemoService + OrderService
 * 3. group/version configuration
 * </pre>
 *
 * <p>The consumer connects directly via {@code directUrl} without registry discovery.
 */
public class DirectProvider {

    private static final int PORT = Integer.parseInt(System.getProperty("port", "10000"));
    private static final String TRANSPORT_FACTORY = System.getProperty("transport", "netty");
    private static final String SERIALIZATION = System.getProperty("serialization", "fastjson2");

    public static void main(String[] args) throws Exception {
        ProtocolConfig protocolConfig = createProtocolConfig();

        /* Export DemoService */
        ServiceConfig<DemoService> demoServiceConfig = new ServiceConfig<>();
        demoServiceConfig.setRef(new DemoServiceImpl());
        demoServiceConfig.setApplication("sample-provider-direct");
        demoServiceConfig.setModule("sample-direct");
        demoServiceConfig.setCheck(true);
        demoServiceConfig.setInterface(DemoService.class);
        demoServiceConfig.setGroup("test");
        demoServiceConfig.setVersion("2.0");
        demoServiceConfig.setProtocol(protocolConfig);
        demoServiceConfig.export();
        System.out.println("DemoService exported (direct mode, no registry).");

        /* Export OrderService */
        ServiceConfig<OrderService> orderServiceConfig = new ServiceConfig<>();
        orderServiceConfig.setRef(new OrderServiceImpl());
        orderServiceConfig.setApplication("sample-provider-direct");
        orderServiceConfig.setModule("sample-direct");
        orderServiceConfig.setInterface(OrderService.class);
        orderServiceConfig.setGroup("test");
        orderServiceConfig.setVersion("2.0");
        orderServiceConfig.setProtocol(protocolConfig);
        orderServiceConfig.export();
        System.out.println("OrderService exported (direct mode, no registry).");

        System.out.println("Provider listening on port " + PORT + ". Consumer should use directUrl=127.0.0.1:" + PORT);
    }

    private static ProtocolConfig createProtocolConfig() {
        ProtocolConfig protocolConfig = new ProtocolConfig();
        protocolConfig.setName(JawsConstants.PROTOCOL_JAWS);
        protocolConfig.setId(protocolConfig.getName());
        protocolConfig.setTransportFactory(TRANSPORT_FACTORY);
        protocolConfig.setSerialization(SERIALIZATION);
        protocolConfig.setPort(PORT);
        return protocolConfig;
    }
}
