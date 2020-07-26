package org.hongxi.summer.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

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

    public static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length);
        GZIPOutputStream gzip = null;
        try {
            gzip = new GZIPOutputStream(bos);
            gzip.write(data);
            gzip.finish();
            return bos.toByteArray();
        } finally {
            if (gzip != null) {
                gzip.close();
            }
        }
    }

    public static byte[] unGzip(byte[] data) throws IOException {
        GZIPInputStream gzip = null;
        try {
            gzip = new GZIPInputStream(new ByteArrayInputStream(data));
            byte[] buf = new byte[2048];
            int size = -1;
            ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length + 1024);
            while ((size = gzip.read(buf, 0, buf.length)) != -1) {
                bos.write(buf, 0, size);
            }
            return bos.toByteArray();
        } finally {
            if (gzip != null) {
                gzip.close();
            }
        }
    }
}
