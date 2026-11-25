package org.hongxi.jaws.sample.store;

import org.hongxi.jaws.store.StoreBroker;
import org.hongxi.jaws.store.StoreConfig;

/**
 * Store broker demo: the RocketMQ-style storage engine exposed over the
 * jaws protocol, direct-connect mode (no registry).
 *
 * <pre>
 * Properties:
 *   -Dport=10911          broker listen port
 *   -DstorePath=...       data directory (default ~/jaws-store-sample)
 *   -DsyncFlush=false     true switches to group-commit SYNC_FLUSH
 * </pre>
 *
 * <p>Run {@code StoreProducerMain} / {@code StoreConsumerMain} against it,
 * or use the one-shot {@code ./run-sample.sh store}.
 */
public class StoreBrokerMain {

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getProperty("port", "10911"));
        StoreConfig config = new StoreConfig();
        config.setStorePath(System.getProperty("storePath",
                System.getProperty("user.home") + "/jaws-store-sample"));
        config.setSyncFlush(Boolean.parseBoolean(System.getProperty("syncFlush", "false")));

        StoreBroker broker = new StoreBroker(config, port);
        broker.start();
        Runtime.getRuntime().addShutdownHook(new Thread(broker::shutdown, "StoreBrokerShutdown"));
        System.out.println("StoreBroker listening on port " + port
                + ", storePath=" + config.getStorePath() + ", syncFlush=" + config.isSyncFlush());
    }
}