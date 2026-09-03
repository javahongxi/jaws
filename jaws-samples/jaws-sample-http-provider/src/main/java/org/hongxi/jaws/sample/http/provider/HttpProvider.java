package org.hongxi.jaws.sample.http.provider;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.ServiceConfig;
import org.hongxi.jaws.sample.api.DemoService;
import org.hongxi.jaws.sample.http.provider.service.DemoServiceImpl;

/**
 * HTTP/1.1 transport provider — uses {@code transportFactory=http} to expose
 * Jaws RPC services as a JSON endpoint accessible from {@code curl} or any
 * standard HTTP client.
 *
 * <pre>
 * Demo scenario:
 * 1. jaws protocol, no registry (export only, skip registration)
 * 2. DemoService published with HTTP/1.1 + JSON transport
 * 3. group/version configuration
 * 4. Testable with plain curl (no --http2-prior-knowledge needed)
 * </pre>
 *
 * <p>After startup, test with:
 * <pre>
 * # Health check
 * curl -i http://localhost:10000/health
 *
 * # RPC invoke
 * curl -X POST http://localhost:10000/invoke \
 *   -H "content-type: application/json" \
 *   -d '{"interface":"org.hongxi.jaws.sample.api.DemoService","method":"hello","group":"test","version":"2.0","args":["lily"]}'
 * </pre>
 */
public class HttpProvider {

    private static final int PORT = Integer.parseInt(System.getProperty("port", "10000"));

    public static void main(String[] args) {
        ProtocolConfig protocolConfig = new ProtocolConfig();
        protocolConfig.setName(JawsConstants.PROTOCOL_JAWS);
        protocolConfig.setId(protocolConfig.getName());
        protocolConfig.setTransportFactory("http");
        protocolConfig.setPort(PORT);

        ServiceConfig<DemoService> serviceConfig = new ServiceConfig<>();
        serviceConfig.setRef(new DemoServiceImpl());
        serviceConfig.setApplication("sample-http-provider");
        serviceConfig.setModule("sample-http");
        serviceConfig.setCheck(true);
        serviceConfig.setInterface(DemoService.class);
        serviceConfig.setGroup("test");
        serviceConfig.setVersion("2.0");
        serviceConfig.setProtocol(protocolConfig);
        serviceConfig.export();

        System.out.println("DemoService exported via HTTP/1.1 (transportFactory=http).");
        System.out.println("Provider listening on port " + PORT);
        System.out.println();
        System.out.println("Test with curl:");
        System.out.println("  curl -i http://localhost:" + PORT + "/health");
        System.out.println("  curl -X POST http://localhost:" + PORT + "/invoke \\");
        System.out.println("    -H \"content-type: application/json\" \\");
        System.out.println("    -d '{\"interface\":\"org.hongxi.jaws.sample.api.DemoService\",\"method\":\"hello\",\"group\":\"test\",\"version\":\"2.0\",\"args\":[\"lily\"]}'");
    }
}
