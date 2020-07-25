package org.hongxi.summer.common;

/**
 * Created by shenhongxi on 2020/6/26.
 */
public class SummerConstants {

    public static final String DEFAULT_CHARSET = "UTF-8";
    public static final String PROTOCOL_SEPARATOR = "://";

    public static final String NODE_TYPE_SERVICE = "service";

    /**
     * netty channel constants start
     */

    public static final short NETTY_MAGIC_TYPE = (short) 0xF1F1;
    public static final int NETTY_HEADER_LENGTH = 16;

    public static final int NETTY_SHARE_CHANNEL_MIN_WORKER_THREADS = 40;
    public static final int NETTY_SHARE_CHANNEL_MAX_WORKER_THREADS = 800;
    public static final int NETTY_NOT_SHARE_CHANNEL_MIN_WORKER_THREADS = 20;
    public static final int NETTY_NOT_SHARE_CHANNEL_MAX_WORKER_THREADS = 200;

    public static final String ASYNC_SUFFIX = "Async";// suffix for async call.

    public static final String DEFAULT_VERSION = "1.0";

}
