package org.hongxi.summer.common;

/**
 * Created by shenhongxi on 2020/6/26.
 */
public class SummerConstants {

    public static final String DEFAULT_CHARSET = "UTF-8";

    /**
     * netty channel constants start
     */

    public static final int NETTY_SHARE_CHANNEL_MIN_WORKER_THREADS = 40;
    public static final int NETTY_SHARE_CHANNEL_MAX_WORKER_THREADS = 800;
    public static final int NETTY_NOT_SHARE_CHANNEL_MIN_WORKER_THREADS = 20;
    public static final int NETTY_NOT_SHARE_CHANNEL_MAX_WORKER_THREADS = 200;
}
