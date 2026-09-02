package org.hongxi.jaws.common;

import java.io.File;
import java.util.regex.Pattern;

/**
 * Framework-wide constants for jaws, covering protocol names and separators,
 * registry protocols (local, zookeeper, nacos), endpoint types, and shared
 * split patterns for addresses.
 * <p>
 * Centralizing these keys keeps URL parsing and registry discovery consistent
 * across modules.
 * <p>
 * Created by shenhongxi on 2020/6/26.
 */
public class JawsConstants {

    public static final String FRAMEWORK_NAME = "jaws";

    public static final String PROTOCOL_SEPARATOR = "://";
    public static final String PATH_SEPARATOR = File.separator;

    public static final Pattern COMMA_SPLIT_PATTERN = Pattern.compile("\\s*[,]+\\s*");

    public static final String ENDPOINT_TYPE_SERVICE = "service";
    public static final String ENDPOINT_TYPE_REFERENCE = "reference";

    /** Attachment key for tag-based routing (gray release). */
    public static final String TAG_ATTACHMENT = "tag";

    public static final String REGISTRY_PROTOCOL_LOCAL = "local";
    public static final String REGISTRY_PROTOCOL_ZOOKEEPER = "zookeeper";
    public static final String REGISTRY_PROTOCOL_NACOS = "nacos";

    public static final String ZOOKEEPER_REGISTRY_NAMESPACE = "/jaws";
    public static final String NACOS_REGISTRY_NAMESPACE = "jaws";

    public static final String PROTOCOL_INJVM = "injvm";
    public static final String PROTOCOL_JAWS = "jaws";

    public static final String METHOD_CONFIG_PREFIX = "method-config.";

    public static final Pattern REGISTRY_SPLIT_PATTERN = Pattern.compile("\\s*[|;]+\\s*");
}
