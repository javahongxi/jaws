package org.hongxi.jaws.harbor.raft;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Local durability layer of the Raft node — the "memory + log/snapshot files"
 * half of the persistent-instance story (no external database, exactly like
 * Nacos's JRaft deployment). Three files under one directory:
 * <ul>
 *   <li>{@code meta.json} — currentTerm and votedFor; rewritten atomically and
 *       fsynced BEFORE a vote or term change is acknowledged, so a crash can
 *       never resurrect a node that forgets it already voted in this term</li>
 *   <li>{@code log.jsonl} — append-only JSON lines, one per {@link LogEntry};
 *       fsynced before an AppendEntries success goes back to the leader</li>
 *   <li>{@code snapshot.json} — the compacted state-machine image plus its
 *       lastIncludedIndex/Term; written atomically, then the log prefix is
 *       rewritten away</li>
 * </ul>
 * Recovery order is snapshot first, then the log suffix after it.
 * <p>
 * Confined to the raft thread like {@link RaftLog}; the file handles stay open
 * for the node's lifetime and are closed by {@link #close()}.
 *
 * @author shenhongxi
 */
public class RaftStorage {

    private static final Logger log = LoggerFactory.getLogger(RaftStorage.class);

    private static final String META_FILE = "meta.json";
    private static final String LOG_FILE = "log.jsonl";
    private static final String SNAPSHOT_FILE = "snapshot.json";

    /** Hard state of one node: the term it lives in and the vote it cast. */
    public record Meta(long currentTerm, String votedFor) {
    }

    /** A persisted state-machine image and the log position it covers through. */
    public record Snapshot(long lastIncludedIndex, long lastIncludedTerm, byte[] data) {
    }

    private final Path dir;
    private FileOutputStream logOut;

    public RaftStorage(Path dir) {
        this.dir = dir;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("cannot create raft data dir " + dir, e);
        }
    }

    public Path dir() {
        return dir;
    }

    // ========================================================================
    // Recovery reads
    // ========================================================================

    /** @return the persisted hard state, or null on a fresh node. */
    public Meta loadMeta() {
        Path file = dir.resolve(META_FILE);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            JSONObject json = JSON.parseObject(Files.readString(file, StandardCharsets.UTF_8));
            return new Meta(json.getLongValue("currentTerm"), json.getString("votedFor"));
        } catch (Exception e) {
            throw new IllegalStateException("corrupt raft meta file " + file, e);
        }
    }

    /** @return the persisted snapshot, or null when none was ever taken. */
    public Snapshot loadSnapshot() {
        Path file = dir.resolve(SNAPSHOT_FILE);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            JSONObject json = JSON.parseObject(Files.readString(file, StandardCharsets.UTF_8));
            byte[] data = Base64.getDecoder().decode(json.getString("data"));
            return new Snapshot(json.getLongValue("lastIncludedIndex"),
                    json.getLongValue("lastIncludedTerm"), data);
        } catch (Exception e) {
            throw new IllegalStateException("corrupt raft snapshot file " + file, e);
        }
    }

    /**
     * Read the persisted log suffix strictly after {@code afterIndex} (the
     * snapshot boundary). A torn tail line from a crash mid-append is dropped:
     * an entry that never reached the disk was never acknowledged either.
     */
    public List<LogEntry> loadLog(long afterIndex) {
        Path file = dir.resolve(LOG_FILE);
        if (!Files.exists(file)) {
            return List.of();
        }
        List<LogEntry> entries = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                JSONObject json;
                try {
                    json = JSON.parseObject(line);
                } catch (Exception torn) {
                    log.warn("[raft] dropping torn log tail line in {}", file);
                    break;
                }
                long index = json.getLongValue("index");
                if (index > afterIndex) {
                    entries.add(new LogEntry(index, json.getLongValue("term"),
                            Base64.getDecoder().decode(json.getString("data"))));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read raft log file " + file, e);
        }
        return entries;
    }

    // ========================================================================
    // Writes (each durable before the caller acknowledges anything)
    // ========================================================================

    /** Persist term/vote atomically; called before responding to any vote. */
    public void saveMeta(long currentTerm, String votedFor) {
        JSONObject json = new JSONObject();
        json.put("currentTerm", currentTerm);
        json.put("votedFor", votedFor);
        atomicWrite(dir.resolve(META_FILE), json.toJSONString());
    }

    /** Append entries to the log file and fsync; the append precedes the ack. */
    public void appendLog(List<LogEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        try {
            BufferedWriter writer = logWriter();
            for (LogEntry each : entries) {
                writer.write(lineOf(each));
                writer.newLine();
            }
            writer.flush();
            logOut.getFD().sync();
        } catch (IOException e) {
            throw new IllegalStateException("cannot append raft log in " + dir, e);
        }
    }

    /**
     * Rewrite the whole log file — used after a conflict truncation or a
     * snapshot compaction removed entries the append-only file still holds.
     */
    public void rewriteLog(List<LogEntry> entries) {
        try {
            closeLogWriter();
            Path file = dir.resolve(LOG_FILE);
            StringBuilder content = new StringBuilder();
            for (LogEntry each : entries) {
                content.append(lineOf(each)).append('\n');
            }
            atomicWrite(file, content.toString());
        } catch (IOException e) {
            throw new IllegalStateException("cannot rewrite raft log in " + dir, e);
        }
    }

    /** Persist a state-machine snapshot atomically at the given log position. */
    public void saveSnapshot(long lastIncludedIndex, long lastIncludedTerm, byte[] data) {
        JSONObject json = new JSONObject();
        json.put("lastIncludedIndex", lastIncludedIndex);
        json.put("lastIncludedTerm", lastIncludedTerm);
        json.put("data", Base64.getEncoder().encodeToString(data));
        atomicWrite(dir.resolve(SNAPSHOT_FILE), json.toJSONString());
    }

    public void close() {
        try {
            closeLogWriter();
        } catch (IOException e) {
            log.warn("[raft] error closing log file in {}: {}", dir, e.getMessage());
        }
    }

    // ========================================================================
    // Internal
    // ========================================================================

    private static String lineOf(LogEntry entry) {
        JSONObject json = new JSONObject();
        json.put("index", entry.index());
        json.put("term", entry.term());
        json.put("data", Base64.getEncoder().encodeToString(entry.data()));
        return json.toJSONString();
    }

    private BufferedWriter logWriter() throws IOException {
        if (logOut == null) {
            logOut = new FileOutputStream(dir.resolve(LOG_FILE).toFile(), true);
        }
        return new BufferedWriter(new OutputStreamWriter(logOut, StandardCharsets.UTF_8));
    }

    private void closeLogWriter() throws IOException {
        if (logOut != null) {
            logOut.close();
            logOut = null;
        }
    }

    /**
     * Write-to-temp, fsync, atomic rename: a crash at any point leaves either
     * the old file or the new one, never a half-written replacement.
     */
    private static void atomicWrite(Path file, String content) {
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp.toFile())) {
                out.write(content.getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("cannot write raft file " + file, e);
        }
    }
}