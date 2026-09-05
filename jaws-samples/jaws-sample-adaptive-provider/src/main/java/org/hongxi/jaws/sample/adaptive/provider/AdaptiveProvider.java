package org.hongxi.jaws.sample.adaptive.provider;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.ServiceConfig;
import org.hongxi.jaws.sample.adaptive.provider.service.DemoServiceImpl;
import org.hongxi.jaws.sample.api.DemoService;

/**
 * Adaptive transport provider — single port serves three protocols simultaneously.
 *
 * <pre>
 * Uses {@code transportFactory=adaptive} to start an AdaptiveServer that
 * auto-detects the protocol from the first bytes of each TCP connection:
 *
 *   1. Jaws binary (0x4A57)  → NettyDecoder + NettyChannelHandler
 *   2. HTTP/2 h2c (PRI *)    → Http2FrameCodec + Http2MultiplexHandler
 *   3. HTTP/1.1 (GET/POST/…) → HttpServerCodec + HttpRequestHandler
 *
 * Demo scenario:
 *   - Multi-protocol on a single port (no separate ports needed)
 *   - Multi-service publishing: DemoService + OrderService + StreamService
 *   - group/version configuration
 * </pre>
 *
 * <p>The consumer connects via jaws binary protocol (netty transport) using
 * {@code directUrl}. HTTP/1.1 and HTTP/2 can be tested with {@code curl}:
 *
 * <pre>
 * # HTTP/1.1 — health check
 * curl -i http://localhost:10000/health
 *
 * # HTTP/1.1 — RPC invoke
 * curl -X POST http://localhost:10000/invoke \
 *   -H "content-type: application/json" \
 *   -d '{"interface":"org.hongxi.jaws.sample.api.DemoService","method":"hello","group":"test","version":"2.0","args":["lily"]}'
 *
 * # HTTP/2 h2c — health check
 * curl --http2-prior-knowledge -i http://localhost:10000/health
 *
 * # HTTP/2 h2c — RPC invoke
 * curl --http2-prior-knowledge -X POST http://localhost:10000/invoke \
 *   -H "content-type: application/json" \
 *   -d '{"interface":"org.hongxi.jaws.sample.api.DemoService","method":"hello","group":"test","version":"2.0","args":["lily"]}'
 * </pre>
 */
public class AdaptiveProvider {

    private static final int PORT = Integer.parseInt(System.getProperty("port", "10000"));
    private static final String SERIALIZATION = System.getProperty("serialization", "fastjson2");

    public static void main(String[] args) {
        ProtocolConfig protocolConfig = createProtocolConfig();

        /* Export DemoService */
        ServiceConfig<DemoService> demoServiceConfig = new ServiceConfig<>();
        demoServiceConfig.setRef(new DemoServiceImpl());
        demoServiceConfig.setApplication("sample-adaptive-provider");
        demoServiceConfig.setModule("sample-adaptive");
        demoServiceConfig.setCheck(true);
        demoServiceConfig.setInterface(DemoService.class);
        demoServiceConfig.setGroup("test");
        demoServiceConfig.setVersion("2.0");
        demoServiceConfig.setProtocol(protocolConfig);
        demoServiceConfig.export();
        System.out.println("DemoService exported (adaptive, single port for all protocols).");

        System.out.println("Adaptive provider listening on port " + PORT
                + " — accepts Jaws binary, HTTP/2, and HTTP/1.1 on the same port.");
        System.out.println();
        System.out.println("Test with curl:");
        System.out.println("  curl -i http://localhost:" + PORT + "/health");
        System.out.println("  curl --http2-prior-knowledge -i http://localhost:" + PORT + "/health");
    }

    private static ProtocolConfig createProtocolConfig() {
        ProtocolConfig protocolConfig = new ProtocolConfig();
        protocolConfig.setName(JawsConstants.PROTOCOL_JAWS);
        protocolConfig.setId(protocolConfig.getName());
        protocolConfig.setTransportFactory("adaptive");
        protocolConfig.setSerialization(SERIALIZATION);
        protocolConfig.setPort(PORT);
        return protocolConfig;
    }
}
