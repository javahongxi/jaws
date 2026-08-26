package org.hongxi.jaws.sample.http2.provider;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.ServiceConfig;
import org.hongxi.jaws.sample.api.DemoService;
import org.hongxi.jaws.sample.api.OrderService;
import org.hongxi.jaws.sample.api.StreamService;
import org.hongxi.jaws.sample.http2.provider.service.DemoServiceImpl;
import org.hongxi.jaws.sample.http2.provider.service.OrderServiceImpl;
import org.hongxi.jaws.sample.http2.provider.service.StreamServiceImpl;

/**
 * HTTP/2 transport provider - same as netty sample but with {@code transportFactory=http2}.
 *
 * <pre>
 * Demo scenario:
 * 1. jaws protocol, no registry (export only, skip registration)
 * 2. Multi-Service publishing - DemoService + OrderService + StreamService
 * 3. group/version configuration
 * 4. Server streaming over HTTP/2 (StreamService with Flow.Publisher)
 * </pre>
 *
 * <p>The consumer connects directly via {@code directUrl} without registry discovery.
 */
public class Http2Provider {

    private static final int PORT = Integer.parseInt(System.getProperty("port", "10000"));
    private static final String SERIALIZATION = System.getProperty("serialization", "fastjson2");

    public static void main(String[] args) {
        ProtocolConfig protocolConfig = createProtocolConfig();

        /* Export DemoService */
        ServiceConfig<DemoService> demoServiceConfig = new ServiceConfig<>();
        demoServiceConfig.setRef(new DemoServiceImpl());
        demoServiceConfig.setApplication("sample-http2-provider");
        demoServiceConfig.setModule("sample-http2");
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
        orderServiceConfig.setApplication("sample-http2-provider");
        orderServiceConfig.setModule("sample-http2");
        orderServiceConfig.setInterface(OrderService.class);
        orderServiceConfig.setGroup("test");
        orderServiceConfig.setVersion("2.0");
        orderServiceConfig.setProtocol(protocolConfig);
        orderServiceConfig.export();
        System.out.println("OrderService exported (direct mode, no registry).");

        /* Export StreamService (server streaming over HTTP/2) */
        ServiceConfig<StreamService> streamServiceConfig = new ServiceConfig<>();
        streamServiceConfig.setRef(new StreamServiceImpl());
        streamServiceConfig.setApplication("sample-http2-provider");
        streamServiceConfig.setModule("sample-http2");
        streamServiceConfig.setInterface(StreamService.class);
        streamServiceConfig.setGroup("test");
        streamServiceConfig.setVersion("2.0");
        streamServiceConfig.setProtocol(protocolConfig);
        streamServiceConfig.export();
        System.out.println("StreamService exported (server streaming over HTTP/2).");

        System.out.println("Provider listening on port " + PORT + ". Consumer should use directUrl=127.0.0.1:" + PORT);
    }

    private static ProtocolConfig createProtocolConfig() {
        ProtocolConfig protocolConfig = new ProtocolConfig();
        protocolConfig.setName(JawsConstants.PROTOCOL_JAWS);
        protocolConfig.setId(protocolConfig.getName());
        protocolConfig.setTransportFactory("http2");
        protocolConfig.setSerialization(SERIALIZATION);
        protocolConfig.setPort(PORT);
        return protocolConfig;
    }
}
