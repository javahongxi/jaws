package org.hongxi.jaws.common;

import java.io.File;
import java.util.regex.Pattern;

/**
 * Created by shenhongxi on 2020/6/26.
 */
public class JawsConstants {

    public static final String FRAMEWORK_NAME = "jaws";

    public static final String DEFAULT_CHARSET = "UTF-8";
    public static final String PROTOCOL_SEPARATOR = "://";
    public static final String PATH_SEPARATOR = File.separator;

    public static final String ACCESS_LOG_SEPARATOR = "|";
    public static final String COMMA_SEPARATOR = ",";
    public static final Pattern COMMA_SPLIT_PATTERN = Pattern.compile("\\s*[,]+\\s*");

    public static final String NODE_TYPE_SERVICE = "service";
    public static final String NODE_TYPE_REFERER = "referer";

    /**
     * netty channel constants start
     */

    public static final short NETTY_MAGIC_TYPE = (short) 0xF1F1;

    public static final int NETTY_SHARE_CHANNEL_MIN_WORKER_THREADS = 40;
    public static final int NETTY_SHARE_CHANNEL_MAX_WORKER_THREADS = 800;
    public static final int NETTY_NOT_SHARE_CHANNEL_MIN_WORKER_THREADS = 20;
    public static final int NETTY_NOT_SHARE_CHANNEL_MAX_WORKER_THREADS = 200;

    public static final int NETTY_TIMEOUT_TIMER_PERIOD = 100;

    public static final String ASYNC_SUFFIX = "Async";// suffix for async call.

    public static final String DEFAULT_VALUE = "default";
    public static final int DEFAULT_INT_VALUE = 0;
    public static final String DEFAULT_VERSION = "1.0";

    // netty client max concurrent request TODO 2W is suitable?
    public static final int NETTY_CLIENT_MAX_REQUEST = 20000;

    // ------------------ jaws protocol constants -----------------
    public static final String JAWS_GROUP = "J_g";
    public static final String JAWS_VERSION = "J_v";
    public static final String JAWS_PATH = "J_p";
    public static final String JAWS_METHOD = "J_m";
    public static final String JAWS_METHOD_DESC = "J_md";
    public static final String JAWS_AUTH = "J_a";
    public static final String JAWS_SOURCE = "J_s";// 调用方来源标识,等同与application
    public static final String JAWS_MODULE = "J_mdu";
    public static final String JAWS_PROXY_PROTOCOL = "J_pp";
    public static final String JAWS_INFO_SIGN = "J_is";
    public static final String JAWS_ERROR = "J_e";
    public static final String JAWS_PROCESS_TIME = "J_pt";

    public static final String CONTENT_LENGTH = "Content-Length";

    public static final String REGISTRY_PROTOCOL_LOCAL = "local";
    public static final String REGISTRY_PROTOCOL_DIRECT = "direct";
    public static final String REGISTRY_PROTOCOL_ZOOKEEPER = "zookeeper";
    public static final String PROTOCOL_INJVM = "injvm";
    public static final String PROTOCOL_JAWS = "jaws";

    public static final String METHOD_CONFIG_PREFIX = "methodconfig.";

    public static final byte FLAG_REQUEST = 0x00;
    public static final byte FLAG_RESPONSE = 0x01;
    public static final byte FLAG_RESPONSE_VOID = 0x03;
    public static final byte FLAG_RESPONSE_EXCEPTION = 0x05;
    public static final byte FLAG_RESPONSE_ATTACHMENT = 0x07;
    public static final byte FLAG_OTHER = (byte) 0xFF;

    public static final Pattern REGISTRY_SPLIT_PATTERN = Pattern.compile("\\s*[|;]+\\s*");
    public static final Pattern QUERY_PARAM_PATTERN = Pattern.compile("\\s*[&]+\\s*");
    public static final String EQUAL_SIGN_SEPARATOR = "=";
    public static final Pattern EQUAL_SIGN_PATTERN = Pattern.compile("\\s*[=]\\s*");

}
