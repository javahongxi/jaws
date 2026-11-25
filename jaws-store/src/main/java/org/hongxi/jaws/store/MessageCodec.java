package org.hongxi.jaws.store;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * CommitLog record layout (big-endian), a minimal cousin of RocketMQ's
 * CommitLog encoding — fixed header, body, then topic at the tail:
 *
 * <pre>
 * offset  field            type
 * 0       totalSize        int
 * 4       magic            int    ("JAWS")
 * 8       queueId          int
 * 12      bodyCrc          int
 * 16      queueOffset      long
 * 24      physicalOffset   long
 * 32      storeTimestamp   long
 * 40      bodyLen          int
 * 44      body             bytes
 * 44+n    topicLen         byte
 * 45+n    topic            bytes
 * </pre>
 *
 * <p>physicalOffset and storeTimestamp are patched inside the global put
 * lock (their values are only known there); everything else is encoded
 * outside it, under the per-topic-queue lock.
 */
public final class MessageCodec {

    public static final int MESSAGE_MAGIC = 0x4A415753; // "JAWS"
    public static final int HEADER_SIZE = 44;
    public static final int PHYSICAL_OFFSET_POS = 24;
    public static final int STORE_TIMESTAMP_POS = 32;

    private MessageCodec() {
    }

    /** Encoded size of a message with the given body and topic. */
    public static int calTotalSize(int bodyLen, int topicBytesLen) {
        return HEADER_SIZE + bodyLen + 1 + topicBytesLen;
    }

    /**
     * Encode into the given buffer starting at its current position.
     * physicalOffset/storeTimestamp are left as zero placeholders.
     */
    public static void encode(StoreMessage message, long queueOffset, ByteBuffer buffer) {
        byte[] body = message.getBody();
        byte[] topicBytes = message.getTopic().getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32();
        crc.update(body);
        buffer.putInt(calTotalSize(body.length, topicBytes.length));
        buffer.putInt(MESSAGE_MAGIC);
        buffer.putInt(message.getQueueId());
        buffer.putInt((int) crc.getValue());
        buffer.putLong(queueOffset);
        buffer.putLong(0L); // physicalOffset, patched under the global lock
        buffer.putLong(0L); // storeTimestamp, patched under the global lock
        buffer.putInt(body.length);
        buffer.put(body);
        buffer.put((byte) topicBytes.length);
        buffer.put(topicBytes);
    }

    /** Decode one record from the slice's current position. */
    public static MessageView decode(ByteBuffer record) {
        MessageView view = new MessageView();
        view.setStoreSize(record.getInt());
        record.getInt(); // magic, already validated by the caller
        view.setQueueId(record.getInt());
        record.getInt(); // bodyCrc, not re-verified on the read path
        view.setQueueOffset(record.getLong());
        view.setCommitLogOffset(record.getLong());
        view.setStoreTimestamp(record.getLong());
        int bodyLen = record.getInt();
        byte[] body = new byte[bodyLen];
        record.get(body);
        view.setBody(body);
        int topicLen = record.get() & 0xFF;
        byte[] topicBytes = new byte[topicLen];
        record.get(topicBytes);
        view.setTopic(new String(topicBytes, StandardCharsets.UTF_8));
        return view;
    }
}