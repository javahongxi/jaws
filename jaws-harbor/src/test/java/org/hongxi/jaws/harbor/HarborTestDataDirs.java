package org.hongxi.jaws.harbor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

/**
 * Unique Raft data directories for tests. Every server a test builds must get
 * its own {@code dataDir}: the default ({@code ~/.harbor/data}) would replay a
 * previous run's committed log into the next one, turning isolated tests into
 * a sequence. Directories are removed by a JVM shutdown hook, so a failed
 * assertion cannot leave them behind either.
 *
 * @author shenhongxi
 */
public final class HarborTestDataDirs {

    private static final List<Path> DIRS = new CopyOnWriteArrayList<>();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(HarborTestDataDirs::cleanup,
                "harbor-test-data-cleanup"));
    }

    private HarborTestDataDirs() {
    }

    /** @return a fresh empty directory path, cleaned up at JVM exit. */
    public static String fresh() {
        try {
            Path dir = Files.createTempDirectory("harbor-raft-test-");
            DIRS.add(dir);
            return dir.toString();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create test data dir", e);
        }
    }

    private static void cleanup() {
        for (Path dir : DIRS) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // Best effort: the OS temp area is reclaimed anyway.
                    }
                });
            } catch (IOException ignored) {
                // Already gone.
            }
        }
    }
}