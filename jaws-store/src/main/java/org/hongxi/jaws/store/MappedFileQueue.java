package org.hongxi.jaws.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The ordered sequence of mapped files forming one logical log (CommitLog
 * or a ConsumeQueue). File names are zero-padded start offsets, so lexical
 * order equals physical order.
 *
 * <p>Simplification vs RocketMQ: file creation is synchronous under a create
 * lock (no AllocateMappedFileService pre-allocation pipeline yet); files are
 * never deleted, so there is no disk-watermark machinery.
 */
public class MappedFileQueue {

    private static final Logger logger = LoggerFactory.getLogger(MappedFileQueue.class);

    private final String storePath;
    private final int mappedFileSize;
    private final CopyOnWriteArrayList<MappedFile> mappedFiles = new CopyOnWriteArrayList<>();
    private final ReentrantLock createLock = new ReentrantLock();

    public MappedFileQueue(String storePath, int mappedFileSize) {
        this.storePath = storePath;
        this.mappedFileSize = mappedFileSize;
    }

    /** Load existing files from disk; every loaded file is marked full and
     * recovery later corrects the write position of the last one. */
    public boolean load() {
        File dir = new File(storePath);
        File[] files = dir.listFiles();
        if (files == null) {
            return true;
        }
        Arrays.sort(files);
        for (File file : files) {
            if (file.length() != mappedFileSize) {
                logger.warn("Skip file {} with unexpected length {}", file.getName(), file.length());
                continue;
            }
            try {
                MappedFile mappedFile = new MappedFile(file.getPath(), mappedFileSize);
                mappedFile.setWrotePosition(mappedFileSize);
                mappedFiles.add(mappedFile);
            } catch (IOException e) {
                logger.error("Failed to load mapped file {}", file.getName(), e);
                return false;
            }
        }
        return true;
    }

    public MappedFile getLastMappedFile() {
        return mappedFiles.isEmpty() ? null : mappedFiles.get(mappedFiles.size() - 1);
    }

    /**
     * Return the last file, creating a new one when absent or full. The new
     * file starts at {@code startOffset} rounded down to a file boundary.
     */
    public MappedFile getLastMappedFile(long startOffset) {
        MappedFile last = getLastMappedFile();
        if (last != null && !last.isFull()) {
            return last;
        }
        createLock.lock();
        try {
            last = getLastMappedFile();
            if (last != null && !last.isFull()) {
                return last;
            }
            long fromOffset = last == null
                    ? startOffset - startOffset % mappedFileSize
                    : last.getFileFromOffset() + mappedFileSize;
            return createMappedFile(fromOffset);
        } finally {
            createLock.unlock();
        }
    }

    /**
     * Force-roll to the successor of the current last file. Needed when the
     * last file is not full yet but its remaining space cannot fit the
     * pending append — getLastMappedFile would keep returning it.
     */
    public MappedFile rollNextFile() {
        createLock.lock();
        try {
            MappedFile last = getLastMappedFile();
            long fromOffset = last == null ? 0 : last.getFileFromOffset() + mappedFileSize;
            return createMappedFile(fromOffset);
        } finally {
            createLock.unlock();
        }
    }

    private MappedFile createMappedFile(long fromOffset) {
        String fileName = String.format("%020d", fromOffset);
        try {
            MappedFile created = new MappedFile(storePath + File.separator + fileName, mappedFileSize);
            mappedFiles.add(created);
            return created;
        } catch (IOException e) {
            logger.error("Failed to create mapped file {}", fileName, e);
            return null;
        }
    }

    public MappedFile findMappedFileByOffset(long offset) {
        MappedFile first = mappedFiles.isEmpty() ? null : mappedFiles.get(0);
        if (first == null) {
            return null;
        }
        int index = (int) (offset / mappedFileSize - first.getFileFromOffset() / mappedFileSize);
        if (index < 0 || index >= mappedFiles.size()) {
            return null;
        }
        return mappedFiles.get(index);
    }

    /** Global end of data: start offset of the last file plus its write position. */
    public long getMaxOffset() {
        MappedFile last = getLastMappedFile();
        return last == null ? 0 : last.getFileFromOffset() + last.getWrotePosition();
    }

    /**
     * Flush every dirty file and return the global flushed offset. Flushing
     * older files first keeps the returned offset a true durability frontier.
     */
    public long flush() {
        for (MappedFile mappedFile : mappedFiles) {
            if (mappedFile.getFlushedPosition() < mappedFile.getWrotePosition()) {
                mappedFile.flush();
            }
        }
        MappedFile last = getLastMappedFile();
        return last == null ? 0 : last.getFileFromOffset() + last.getFlushedPosition();
    }

    public void shutdown() {
        flush();
        for (MappedFile mappedFile : mappedFiles) {
            mappedFile.close();
        }
    }

    public String getStorePath() {
        return storePath;
    }

    public int getMappedFileSize() {
        return mappedFileSize;
    }
}