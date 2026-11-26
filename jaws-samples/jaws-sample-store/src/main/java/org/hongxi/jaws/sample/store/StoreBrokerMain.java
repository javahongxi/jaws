package org.hongxi.jaws.sample.store;

import org.hongxi.jaws.store.StoreBroker;
import org.hongxi.jaws.store.StoreConfig;

import java.util.List;

/**
 * Store broker demo: the RocketMQ-style storage engine exposed over the
 * jaws protocol. Direct-connect by default; set -Dregistry to join a
 * multi-master shard (each instance needs a distinct brokerName/storePath).
 *
 * <pre>
 * Properties:
 *   -Dport=10911          broker listen port
 *   -DstorePath=...       data directory (default ~/jaws-store-sample)
 *   -DsyncFlush=false     true switches to group-commit SYNC_FLUSH
 *   -DbrokerName=broker   stable identity, persisted under storePath
 *   -Dregistry=harbor://127.0.0.1:19848   register for discovery (optional)
 * </pre>
 *
 * <p>Run {@code StoreProducerMain} / {@code StoreConsumerMain} against it,
 * or use the one-shot {@code ./run-sample.sh store} (single instance) /
 * {@code ./run-sample.sh store-cluster} (two instances + harbor registry).
 */
public class StoreBrokerMain {

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getProperty("port", "10911"));
        StoreConfig config = new StoreConfig();
        config.setStorePath(System.getProperty("storePath",
                System.getProperty("user.home") + "/jaws-store-sample"));
        config.setSyncFlush(Boolean.parseBoolean(System.getProperty("syncFlush", "false")));
        config.setBrokerName(System.getProperty("brokerName", "broker"));

        String registry = System.getProperty("registry");
        StoreBroker broker = registry == null
                ? new StoreBroker(config, port)
                : new StoreBroker(config, port, "fastjson2", List.of(StoreSampleRegistry.parse(registry)));
        broker.start();
        Runtime.getRuntime().addShutdownHook(new Thread(broker::shutdown, "StoreBrokerShutdown"));
        System.out.println("StoreBroker '" + config.getBrokerName() + "' listening on port " + port
                + ", storePath=" + config.getStorePath() + ", syncFlush=" + config.isSyncFlush()
                + (registry == null ? "" : ", registry=" + registry));
    }
}