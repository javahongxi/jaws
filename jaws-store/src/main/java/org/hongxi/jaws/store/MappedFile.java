package org.hongxi.jaws.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One fixed-size file mapped into the process address space via mmap.
 *
 * <p>Writing is a memcpy into the mapped slice — no write syscall, no JVM
 * heap copy; dirty pages are written back by the kernel. This is the Java
 * counterpart of RocketMQ's DefaultMappedFile.
 *
 * <p>Simplification vs RocketMQ: mapped files are append-only; a selected slice
 * stays valid for the life of the mapping and no reference counting is done.
 * Explicit unmapping is left to GC (JDK has no public unmap API); the channel is
 * closed on shutdown or when the cleanup ticker reclaims an expired file. A
 * reader that grabs a slice of a file the sweeper deletes concurrently is the
 * accepted risk of that simplification — cleanup only targets files whose data
 * is already fully dispatched and out of retention, which live readers have
 * moved past.
 *
 * <p>Freshly created files are warmed page by page at creation time (the
 * counterpart of RocketMQ's mlock/MAP_POPULATE warm-up): a sparse 1G mapping
 * would otherwise turn the first burst of concurrent appends into a page
 * fault storm, stalling every writer inside the kernel at once.
 */
public class MappedFile {

    private static final Logger logger = LoggerFactory.getLogger(MappedFile.class);

    private final File file;
    private final int fileSize;
    private final long fileFromOffset;

    private FileChannel fileChannel;
    private MappedByteBuffer mappedByteBuffer;

    private final AtomicInteger wrotePosition = new AtomicInteger(0);
    private final AtomicInteger flushedPosition = new AtomicInteger(0);
    private volatile boolean closed;

    public MappedFile(String fileName, int fileSize) throws IOException {
        this.file = new File(fileName);
        this.fileSize = fileSize;
        this.fileFromOffset = Long.parseLong(this.file.getName());
        init();
    }

    private void init() throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create directory " + parent);
        }
        boolean created = file.createNewFile();
        RandomAccessFile raf = new RandomAccessFile(file, "rw");
        try {
            fileChannel = raf.getChannel();
            // map() extends the file to fileSize when needed
            mappedByteBuffer = fileChannel.map(FileChannel.MapMode.READ_WRITE, 0, fileSize);
        } catch (IOException e) {
            raf.close();
            throw e;
        }
        if (created) {
            warmUp();
            logger.info("Created mapped file {}, size {}", file.getName(), fileSize);
        }
    }

    /**
     * Touch one byte per page so every page is faulted in and backed by an
     * allocated block before traffic arrives, then force once so the zero
     * pages are written back here — otherwise the first real fsync would
     * carry a whole file's worth of dirty warm-up pages. Runs once per new
     * file under the caller's create lock, keeping the cost off the append
     * path (RocketMQ's warmMappedFile does the same write-then-force).
     */
    private void warmUp() throws IOException {
        int pageSize = 4096;
        for (int pos = 0; pos < fileSize; pos += pageSize) {
            mappedByteBuffer.put(pos, (byte) 0);
        }
        fileChannel.force(false);
    }

    /**
     * Append the remaining bytes of data at the current write position.
     * The caller must guarantee capacity and external serialization.
     *
     * @return bytes written
     */
    public int appendMessage(ByteBuffer data) {
        int wrote = data.remaining();
        ByteBuffer slice = mappedByteBuffer.slice();
        slice.position(wrotePosition.get());
        slice.put(data);
        wrotePosition.addAndGet(wrote);
        return wrote;
    }

    /**
     * Return a slice covering [pos, pos + size) — a view over the page cache,
     * not a copy.
     */
    public ByteBuffer selectMappedBuffer(int pos, int size) {
        ByteBuffer slice = mappedByteBuffer.slice();
        slice.position(pos);
        slice.limit(pos + size);
        return slice;
    }

    /** Write bytes at an absolute position (used by ConsumeQueue indexing). */
    public void putBytes(int pos, byte[] data) {
        ByteBuffer slice = mappedByteBuffer.slice();
        slice.position(pos);
        slice.put(data);
        wrotePosition.accumulateAndGet(pos + data.length, Math::max);
    }

    public int getInt(int pos) {
        return mappedByteBuffer.getInt(pos);
    }

    public byte getByte(int pos) {
        return mappedByteBuffer.get(pos);
    }

    public long getLong(int pos) {
        return mappedByteBuffer.getLong(pos);
    }

    /**
     * fsync up to the current write position.
     *
     * @return the flushed position after this call
     */
    public int flush() {
        int wrote = wrotePosition.get();
        if (closed || wrote == flushedPosition.get()) {
            return flushedPosition.get();
        }
        try {
            fileChannel.force(false);
            flushedPosition.set(wrote);
        } catch (IOException e) {
            if (e instanceof ClosedByInterruptException) {
                // The JDK closes the channel when a thread is interrupted inside
                // force(). MessageStore.shutdown() interrupts the flush tickers
                // on purpose, so this is an expected exit, not a disk failure —
                // same stopped-before-interrupt rationale as the reput guard.
                // Mirror the closed channel so the final flush attempted by
                // MappedFileQueue.shutdown stays silent instead of reporting a
                // ClosedChannelException against the dead channel as an error.
                // Losing the tail flush is harmless: page cache survives process
                // exit, and ConsumeQueue is rebuilt from the CommitLog anyway.
                closed = true;
                logger.debug("Flush of {} interrupted by shutdown, channel closed", file.getName());
            } else if (!closed) {
                logger.error("Failed to flush {}", file.getName(), e);
            }
        }
        return flushedPosition.get();
    }

    public void close() {
        closed = true;
        try {
            fileChannel.close();
        } catch (IOException e) {
            logger.warn("Failed to close {}", file.getName(), e);
        }
    }

    /**
     * Close the channel and delete the backing file. Used by the cleanup ticker
     * to reclaim an expired CommitLog or ConsumeQueue file; the caller removes
     * it from its queue only after this returns.
     *
     * @return true when the file was removed from disk
     */
    public boolean destroy() {
        close();
        boolean deleted = file.delete();
        if (!deleted) {
            logger.warn("Failed to delete mapped file {}", file.getName());
        }
        return deleted;
    }

    /** Last-modified time of the backing file, the age basis for retention. */
    public long getLastModifiedTime() {
        return file.lastModified();
    }

    public boolean isFull() {
        return wrotePosition.get() == fileSize;
    }

    public int getRemaining() {
        return fileSize - wrotePosition.get();
    }

    public long getFileFromOffset() {
        return fileFromOffset;
    }

    public int getFileSize() {
        return fileSize;
    }

    public int getWrotePosition() {
        return wrotePosition.get();
    }

    public void setWrotePosition(int position) {
        wrotePosition.set(position);
    }

    public int getFlushedPosition() {
        return flushedPosition.get();
    }

    public String getFileName() {
        return file.getName();
    }
}