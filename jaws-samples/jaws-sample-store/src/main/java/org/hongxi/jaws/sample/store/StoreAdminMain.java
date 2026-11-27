package org.hongxi.jaws.sample.store;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.ReferenceConfig;
import org.hongxi.jaws.store.AdminViewResult;
import org.hongxi.jaws.store.PutResult;
import org.hongxi.jaws.store.StoreAdminService;
import org.hongxi.jaws.store.StoreBroker;
import org.hongxi.jaws.store.StoreMessage;
import org.hongxi.jaws.store.client.StoreProducer;

import java.util.List;

/**
 * Store admin demo: look one message back up by its physical CommitLog offset
 * over the jaws binary protocol — the same transport the producer and consumer
 * use, so the broker needs no extra port or HTTP exposure.
 *
 * <pre>
 * Properties:
 *   -DdirectUrl=127.0.0.1:10911   broker address (direct mode)
 *   -Dregistry=harbor://127.0.0.1:19848   registry mode, overrides directUrl
 *   -Dtopic=store-demo            topic for the seeded message, queue 0
 * </pre>
 *
 * <p>The demo is self-contained: it appends one message (a put returns its
 * commitLogOffset, which is exactly the "msgId" an admin would hold), reads it
 * back through {@link StoreAdminService#viewMessageAsync(long)} to show the
 * decoded plaintext, then queries a bogus offset to show the NOT_FOUND answer.
 */
public class StoreAdminMain {

    public static void main(String[] args) throws Exception {
        String directUrl = System.getProperty("directUrl", "127.0.0.1:10911");
        String topic = System.getProperty("topic", "store-demo");
        String registry = System.getProperty("registry");

        // Step 1: append one message so we have a real physical offset to look up.
        StoreProducer producer = registry == null
                ? new StoreProducer(directUrl)
                : new StoreProducer(List.of(StoreSampleRegistry.parse(registry)));
        String body = "admin-demo-" + System.currentTimeMillis();
        PutResult put = producer.sendSync(StoreMessage.of(topic, 0, body));
        producer.shutdown();
        if (!put.isOk()) {
            throw new IllegalStateException("Seed put rejected: " + put.getStatus());
        }
        long offset = put.getCommitLogOffset();
        System.out.printf("Put ok: topic=%s commitLogOffset=%d%n", topic, offset);

        // Step 2: read it back straight from the CommitLog by that offset.
        ReferenceConfig<StoreAdminService> reference = baseReference(directUrl, registry);
        StoreAdminService admin = reference.getRef();
        try {
            AdminViewResult found = admin.viewMessageAsync(offset).get();
            System.out.printf("viewMessage(%d): status=%s%n", offset, found.getStatus());
            if (found.isFound()) {
                System.out.printf("  topic=%s queueId=%d queueOffset=%d body=%s%n",
                        found.getMessage().getTopic(), found.getMessage().getQueueId(),
                        found.getMessage().getQueueOffset(), found.getMessage().bodyAsString());
            }

            // Step 3: an offset past the end of the log resolves to NOT_FOUND, not an error.
            AdminViewResult missing = admin.viewMessageAsync(Long.MAX_VALUE / 2).get();
            System.out.printf("viewMessage(MAX/2): status=%s found=%s%n",
                    missing.getStatus(), missing.isFound());
        } finally {
            reference.destroy();
        }
    }

    private static ReferenceConfig<StoreAdminService> baseReference(String directUrl, String registry) {
        ProtocolConfig protocolConfig = new ProtocolConfig();
        protocolConfig.setName(JawsConstants.PROTOCOL_JAWS);
        protocolConfig.setId(protocolConfig.getName());
        protocolConfig.setTransportFactory("netty");
        protocolConfig.setSerialization("fastjson2");

        ReferenceConfig<StoreAdminService> reference = new ReferenceConfig<>();
        reference.setInterface(StoreAdminService.class);
        reference.setApplication("jaws-store-admin");
        reference.setModule("store");
        reference.setGroup(StoreBroker.GROUP);
        reference.setVersion(StoreBroker.VERSION);
        reference.setRequestTimeout(3000);
        reference.setProtocol(protocolConfig);
        if (registry == null) {
            reference.setDirectUrl(directUrl);
        } else {
            reference.setRegistries(List.of(StoreSampleRegistry.parse(registry)));
            reference.setCheck(false);
        }
        return reference;
    }
}
