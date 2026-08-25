package org.hongxi.jaws.sample.stream.provider;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.ServiceConfig;
import org.hongxi.jaws.sample.api.StreamService;
import org.hongxi.jaws.sample.stream.provider.service.StreamServiceImpl;

/**
 * Stream-mode provider - demonstrates server-streaming RPC over HTTP/2.
 *
 * <pre>
 * Demo scenario:
 * 1. jaws protocol + HTTP/2 transport (required for streaming)
 * 2. StreamService - server-streaming with Flow.Publisher
 * 3. directUrl mode - no registry dependency
 * </pre>
 *
 * <p>Run with: {@code java -Dport=10000 StreamProvider}
 */
public class StreamProvider {

    private static final int PORT = Integer.parseInt(System.getProperty("port", "10000"));
    private static final String SERIALIZATION = System.getProperty("serialization", "fastjson2");

    public static void main(String[] args) {
        ProtocolConfig protocolConfig = createProtocolConfig();

        /* Export StreamService */
        ServiceConfig<StreamService> streamServiceConfig = new ServiceConfig<>();
        streamServiceConfig.setRef(new StreamServiceImpl());
        streamServiceConfig.setApplication("sample-stream-provider");
        streamServiceConfig.setModule("sample-stream");
        streamServiceConfig.setInterface(StreamService.class);
        streamServiceConfig.setGroup("test");
        streamServiceConfig.setVersion("2.0");
        streamServiceConfig.setProtocol(protocolConfig);
        streamServiceConfig.export();
        System.out.println("StreamService exported (stream mode, HTTP/2 transport).");

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
