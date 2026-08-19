package org.hongxi.jaws.common;

import java.io.File;
import java.util.regex.Pattern;

/**
 * Created by shenhongxi on 2020/6/26.
 */
public class JawsConstants {

    public static final String FRAMEWORK_NAME = "jaws";

    public static final String PROTOCOL_SEPARATOR = "://";
    public static final String PATH_SEPARATOR = File.separator;

    public static final String ACCESS_LOG_SEPARATOR = "|";
    public static final String COMMA_SEPARATOR = ",";
    public static final Pattern COMMA_SPLIT_PATTERN = Pattern.compile("\\s*[,]+\\s*");

    public static final String NODE_TYPE_SERVICE = "service";
    public static final String NODE_TYPE_REFERENCE = "reference";

    public static final short NETTY_MAGIC_TYPE = (short) 0xF1F1;
    public static final int NETTY_CLIENT_MAX_REQUEST = 20000;

    // RpcContext attribute key for async call flag.
    public static final String ASYNC_FLAG = "async";

    public static final String DEFAULT_VALUE = "default";

    public static final String REGISTRY_PROTOCOL_LOCAL = "local";
    public static final String REGISTRY_PROTOCOL_ZOOKEEPER = "zookeeper";

    public static final String ZOOKEEPER_REGISTRY_NAMESPACE = "/jaws";
    public static final String NACOS_REGISTRY_NAMESPACE = "jaws";

    public static final String PROTOCOL_INJVM = "injvm";
    public static final String PROTOCOL_JAWS = "jaws";

    public static final String METHOD_CONFIG_PREFIX = "methodconfig.";

    public static final byte FLAG_REQUEST = 0x00;
    public static final byte FLAG_RESPONSE = 0x01;
    public static final byte FLAG_RESPONSE_VOID = 0x03;
    public static final byte FLAG_RESPONSE_EXCEPTION = 0x05;
    public static final byte FLAG_OTHER = (byte) 0xFF;

    public static final Pattern REGISTRY_SPLIT_PATTERN = Pattern.compile("\\s*[|;]+\\s*");
}
