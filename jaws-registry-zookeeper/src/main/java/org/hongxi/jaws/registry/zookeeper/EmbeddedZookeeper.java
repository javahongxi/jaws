package org.hongxi.jaws.registry.zookeeper;

import org.apache.curator.test.TestingServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Created by shenhongxi on 2021/4/24.
 */
public class EmbeddedZookeeper implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(EmbeddedZookeeper.class);

    private TestingServer testingServer;

    public EmbeddedZookeeper(int port) throws Exception {
        String dataDir = System.getProperty("user.home") + File.separator + "test_zk_data";
        Path path = Paths.get(dataDir);
        Files.createDirectories(path);

        File dir = new File(dataDir + File.separator + System.nanoTime());

        // 检测端口是否被占用
        if (isPortInUse(port)) {
            log.warn("Port {} is already in use, skip starting embedded ZK server", port);
            return;
        }

        testingServer = new TestingServer(port, dir);
    }

    /**
     * 检测端口是否被占用
     */
    private boolean isPortInUse(int port) {
        try (ServerSocketChannel channel = ServerSocketChannel.open()) {
            channel.socket().bind(new InetSocketAddress(port));
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    @Override
    public void close() throws IOException {
        if (testingServer != null) {
            testingServer.stop();
        }
    }
}