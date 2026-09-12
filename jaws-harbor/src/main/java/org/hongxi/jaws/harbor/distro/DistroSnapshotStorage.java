package org.hongxi.jaws.harbor.distro;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import org.hongxi.jaws.harbor.model.ClientSyncData;
import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Local file-based snapshot persistence for Distro protocol data.
 * <p>
 * Periodically saves all client session data to a local JSON file so that
 * a restarted node can recover service registry data even when no peer is
 * available to pull from.  Matches the Nacos {@code DiskStorage} concept
 * used by {@code com.alibaba.nacos.core.distributed.distro.component.DistroDataStorage}.
 * <p>
 * Write operations use atomic rename (write to temp file, then move) to
 * prevent corruption from partial writes during crashes.
 *
 * @author shenhongxi
 */
public class DistroSnapshotStorage {

    private static final Logger log = LoggerFactory.getLogger(DistroSnapshotStorage.class);

    private static final String SNAPSHOT_FILE_NAME = "naming_snapshot.json";
    private static final String TEMP_FILE_NAME = "naming_snapshot.json.tmp";
    private static final String DEFAULT_SNAPSHOT_DIR = ".jaws/harbor/snapshot";

    /**
     * URL parameter for the local snapshot directory.
     * Default: {@code ~/.jaws/harbor/snapshot/}
     */
    public static final String PARAM_SNAPSHOT_DIR = "snapshotDir";

    private final Path snapshotDir;

    /**
     * Create with an explicit directory path (for tests using {@code @TempDir}).
     */
    public DistroSnapshotStorage(Path snapshotDir) {
        this.snapshotDir = snapshotDir;
        ensureDir();
    }

    /**
     * Create from URL configuration.
     * Reads {@link #PARAM_SNAPSHOT_DIR} from URL parameters,
     * falling back to {@code ~/.jaws/harbor/snapshot/}.
     */
    public DistroSnapshotStorage(URL url) {
        this(resolveDir(url));
    }

    private static Path resolveDir(URL url) {
        String dirPath = url.getParameter(PARAM_SNAPSHOT_DIR);
        return dirPath != null
                ? Paths.get(dirPath)
                : Paths.get(System.getProperty("user.home"), DEFAULT_SNAPSHOT_DIR);
    }

    /**
     * Save client data snapshot to the local file.
     * Uses atomic write: data is written to a temp file first, then
     * renamed to the snapshot file to prevent corruption on crash.
     *
     * @param clientDataList the client data to persist
     */
    public void saveSnapshot(List<ClientSyncData> clientDataList) {
        if (clientDataList == null || clientDataList.isEmpty()) {
            return;
        }
        ensureDir();
        Path tempFile = snapshotDir.resolve(TEMP_FILE_NAME);
        Path snapshotFile = snapshotDir.resolve(SNAPSHOT_FILE_NAME);
        try {
            byte[] data = JSON.toJSONBytes(clientDataList);
            Files.write(tempFile, data);
            Files.move(tempFile, snapshotFile,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            log.debug("[harbor] snapshot saved: {} clients, {} bytes",
                    clientDataList.size(), data.length);
        } catch (IOException e) {
            log.warn("[harbor] failed to save snapshot: {}", e.getMessage());
        }
    }

    /**
     * Load the most recent snapshot from the local file.
     *
     * @return the loaded client data, or {@code null} if no snapshot exists
     *         or the file is unreadable
     */
    public List<ClientSyncData> loadSnapshot() {
        Path snapshotFile = snapshotDir.resolve(SNAPSHOT_FILE_NAME);
        if (!Files.exists(snapshotFile)) {
            log.info("[harbor] no local snapshot found at {}", snapshotFile);
            return null;
        }
        try {
            byte[] data = Files.readAllBytes(snapshotFile);
            if (data.length == 0) {
                log.warn("[harbor] local snapshot is empty: {}", snapshotFile);
                return null;
            }
            List<ClientSyncData> result = JSON.parseObject(
                    new String(data, StandardCharsets.UTF_8),
                    new TypeReference<>() {});
            if (result != null) {
                log.info("[harbor] local snapshot loaded: {} clients from {}",
                        result.size(), snapshotFile);
            }
            return result;
        } catch (IOException e) {
            log.warn("[harbor] failed to load snapshot: {}", e.getMessage());
            return null;
        }
    }

    /**
     * @return the path to the snapshot file (for diagnostics)
     */
    public Path getSnapshotPath() {
        return snapshotDir.resolve(SNAPSHOT_FILE_NAME);
    }

    private void ensureDir() {
        try {
            Files.createDirectories(snapshotDir);
        } catch (IOException e) {
            log.warn("[harbor] failed to create snapshot directory {}: {}",
                    snapshotDir, e.getMessage());
        }
    }
}
