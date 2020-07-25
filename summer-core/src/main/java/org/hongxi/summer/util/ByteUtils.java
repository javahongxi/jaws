package org.hongxi.summer.util;

/**
 * Created by shenhongxi on 2020/7/25.
 */
public class ByteUtils {

    /**
     * 把byte数组中off开始的2个字节，转为short类型，高位在前
     *
     * @param b
     * @param off
     */
    public static short bytes2short(byte[] b, int off) {
        return (short) (((b[off + 1] & 0xFF)) + ((b[off] & 0xFF) << 8));
    }
}
