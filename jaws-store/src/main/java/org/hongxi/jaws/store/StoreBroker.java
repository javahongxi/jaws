package org.hongxi.jaws.store;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.ServiceConfig;
import org.hongxi.jaws.serialization.FastJson2Serialization;
import org.hongxi.jaws.serialization.ObjectInput;
import org.hongxi.jaws.serialization.ObjectOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * Assembles the storage engine and exposes it over the jaws protocol —
 * the store's broker side. Direct-connect mode, no registry: the point of
 * this module is the storage kernel and the protocol under load, not
 * service discovery.
 *
 * <p>Typical scenario: embedded in a demo main or a test to run a real
 * broker on a local port, with StoreProducer/StoreConsumer on the other end.
 */
public class StoreBroker {

    private static final Logger logger = LoggerFactory.getLogger(StoreBroker.class);

    public static final String GROUP = "store";
    public static final String VERSION = "1.0";

    private final MessageStore messageStore;
    private final ServiceConfig<StoreService> serviceConfig;
    private final String serialization;
    private final int port;

    public StoreBroker(StoreConfig storeConfig, int port) {
        this(storeConfig, port, "fastjson2");
    }

    public StoreBroker(StoreConfig storeConfig, int port, String serialization) {
        this.messageStore = new MessageStore(storeConfig);
        this.serialization = serialization;
        this.port = port;

        ProtocolConfig protocolConfig = new ProtocolConfig();
        protocolConfig.setName(JawsConstants.PROTOCOL_JAWS);
        protocolConfig.setId(protocolConfig.getName());
        protocolConfig.setTransportFactory("netty");
        protocolConfig.setSerialization(serialization);
        protocolConfig.setPort(port);
        // A storage broker lives on bursts: the default zero-capacity worker
        // queue caps in-flight tasks at maxWorkerThreads and fail-fasts the
        // rest, so give the pool a real buffer (RocketMQ's sendMessagePool
        // queues 10000). Pre-start the full pool so the first burst does not
        // pay on-demand thread creation either.
        protocolConfig.setWorkerQueueSize(1000);
        protocolConfig.setMinWorkerThreads(200);

        serviceConfig = new ServiceConfig<>();
        serviceConfig.setRef(new StoreServiceImpl());
        serviceConfig.setApplication("jaws-store");
        serviceConfig.setModule("store");
        serviceConfig.setInterface(StoreService.class);
        serviceConfig.setGroup(GROUP);
        serviceConfig.setVersion(VERSION);
        serviceConfig.setProtocol(protocolConfig);
    }

    public void start() throws IOException {
        messageStore.start();
        warmUpSerialization();
        serviceConfig.export();
        logger.info("StoreBroker listening on port {}", port);
    }

    /**
     * Round-trip every wire POJO once before accepting traffic. The first
     * JSONB call triggers fastjson2's static initialization plus reader and
     * writer construction; under a cold burst all business threads pile up
     * on that class-init monitor at once and the pool exhausts before a
     * single request completes. Paying that cost here keeps it single-threaded.
     */
    private void warmUpSerialization() throws IOException {
        if (!"fastjson2".equals(serialization)) {
            return;
        }
        FastJson2Serialization codec = new FastJson2Serialization();
        Object[] samples = {
                StoreMessage.of("warmup", 0, "warmup"),
                new PutResult(PutStatus.PUT_OK, 0, 0),
                new PullResult(PullStatus.FOUND, 0, 0, 0),
                new MessageView()
        };
        for (Object sample : samples) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutput output = codec.serialize(bytes)) {
                output.writeObject(sample);
            }
            try (ObjectInput input = codec.deserialize(new ByteArrayInputStream(bytes.toByteArray()))) {
                input.readObject(sample.getClass());
            } catch (ClassNotFoundException e) {
                throw new IOException("Serialization warm-up failed for " + sample.getClass(), e);
            }
        }
        logger.info("Serialization warmed up for {} wire types", samples.length);
    }

    public void shutdown() {
        serviceConfig.unexport();
        messageStore.shutdown();
    }

    public MessageStore getMessageStore() {
        return messageStore;
    }

    private class StoreServiceImpl implements StoreService {

        @Override
        public CompletableFuture<PutResult> putAsync(StoreMessage message) {
            return messageStore.putMessageAsync(message);
        }

        @Override
        public CompletableFuture<PullResult> pullAsync(String topic, int queueId, long offset,
                                                       int maxCount, long suspendTimeoutMillis) {
            return messageStore.getMessageAsync(topic, queueId, offset, maxCount, suspendTimeoutMillis);
        }
    }
}