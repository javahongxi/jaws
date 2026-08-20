package org.hongxi.jaws.codec;

/**
 * Utility class for converting between primitive types and byte arrays
 * using big-endian (network byte order) encoding.
 *
 * <p>All methods operate on a specified offset within the byte array,
 * allowing multiple values to be packed into a single buffer.
 *
 * @author shenhongxi
 * @since 2020-07-25
 */
public class Bytes {

    /**
     * Reads 8 bytes from the given byte array starting at the specified offset
     * and converts them into a {@code long} value in big-endian order.
     *
     * @param bytes the source byte array
     * @param off   the starting offset in the byte array
     * @return the decoded long value
     */
    public static long bytes2long(byte[] bytes, int off) {
        return ((bytes[off + 7] & 0xFFL)) + ((bytes[off + 6] & 0xFFL) << 8) + ((bytes[off + 5] & 0xFFL) << 16)
                + ((bytes[off + 4] & 0xFFL) << 24) + ((bytes[off + 3] & 0xFFL) << 32) + ((bytes[off + 2] & 0xFFL) << 40)
                + ((bytes[off + 1] & 0xFFL) << 48) + (((long) bytes[off]) << 56);
    }

    /**
     * Writes a {@code long} value into the given byte array at the specified offset,
     * encoding it as 8 bytes in big-endian order (most significant byte first).
     *
     * @param value the long value to encode
     * @param bytes the destination byte array
     * @param off   the starting offset in the byte array
     */
    public static void long2bytes(long value, byte[] bytes, int off) {
        bytes[off + 7] = (byte) value;
        bytes[off + 6] = (byte) (value >>> 8);
        bytes[off + 5] = (byte) (value >>> 16);
        bytes[off + 4] = (byte) (value >>> 24);
        bytes[off + 3] = (byte) (value >>> 32);
        bytes[off + 2] = (byte) (value >>> 40);
        bytes[off + 1] = (byte) (value >>> 48);
        bytes[off] = (byte) (value >>> 56);
    }

    /**
     * Reads 4 bytes from the given byte array starting at the specified offset
     * and converts them into an {@code int} value in big-endian order.
     *
     * @param bytes the source byte array
     * @param off   the starting offset in the byte array
     * @return the decoded int value
     */
    public static int bytes2int(byte[] bytes, int off) {
        return ((bytes[off + 3] & 0xFF)) + ((bytes[off + 2] & 0xFF) << 8) + ((bytes[off + 1] & 0xFF) << 16) + ((bytes[off]) << 24);
    }

    /**
     * Writes an {@code int} value into the given byte array at the specified offset,
     * encoding it as 4 bytes in big-endian order (most significant byte first).
     *
     * @param value the int value to encode
     * @param bytes the destination byte array
     * @param off   the starting offset in the byte array
     */
    public static void int2bytes(int value, byte[] bytes, int off) {
        bytes[off + 3] = (byte) value;
        bytes[off + 2] = (byte) (value >>> 8);
        bytes[off + 1] = (byte) (value >>> 16);
        bytes[off] = (byte) (value >>> 24);
    }

    /**
     * Reads 2 bytes from the given byte array starting at the specified offset
     * and converts them into a {@code short} value in big-endian order.
     *
     * @param b   the source byte array
     * @param off the starting offset in the byte array
     * @return the decoded short value
     */
    public static short bytes2short(byte[] b, int off) {
        return (short) (((b[off + 1] & 0xFF)) + ((b[off] & 0xFF) << 8));
    }

    /**
     * Writes a {@code short} value into the given byte array at the specified offset,
     * encoding it as 2 bytes in big-endian order (most significant byte first).
     *
     * @param value the short value to encode
     * @param bytes the destination byte array
     * @param off   the starting offset in the byte array
     */
    public static void short2bytes(short value, byte[] bytes, int off) {
        bytes[off + 1] = (byte) value;
        bytes[off] = (byte) (value >>> 8);
    }
}
