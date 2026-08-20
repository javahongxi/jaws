package org.hongxi.jaws.serialization;

import com.alibaba.fastjson2.filter.ContextAutoTypeBeforeHandler;
import com.alibaba.fastjson2.util.TypeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

import static com.alibaba.fastjson2.util.TypeUtils.loadClass;

/**
 * Fastjson2 deserialization security filter.
 * <p>
 * Inspired by Dubbo's Fastjson2SecurityManager, this filter controls AutoType class loading
 * during deserialization through allow/deny list mechanisms to prevent malicious class injection attacks.
 * <p>
 * Two check modes are supported:
 * <ul>
 *   <li>{@link CheckStatus#STRICT} - Strict mode: classes not in the allow list are rejected immediately</li>
 *   <li>{@link CheckStatus#WARN} - Warn mode: classes not in the allow list are allowed but a warning is logged</li>
 * </ul>
 */
public class Fastjson2SecurityFilter extends ContextAutoTypeBeforeHandler {

    private static final Logger log = LoggerFactory.getLogger(Fastjson2SecurityFilter.class);

    /**
     * Security check mode.
     */
    public enum CheckStatus {
        /**
         * Strict mode: classes not in the allow list are rejected immediately.
         */
        STRICT,
        /**
         * Warn mode: classes not in the allow list are allowed but a warning is logged.
         */
        WARN
    }

    /**
     * Built-in deny list prefixes. These classes are never allowed to be deserialized.
     */
    private static final String[] DEFAULT_DENY_PREFIXES = {
            "java.lang.Runtime",
            "java.lang.ProcessBuilder",
            "java.lang.ProcessImpl",
            "java.lang.UNIXProcess",
            "javax.management.",
            "javax.naming.",
            "javax.script.",
            "javax.servlet.",
            "javax.imageio.",
            "java.awt.",
            "sun.",
            "jdk.",
            "org.apache.commons.collections.functors.",
            "org.apache.commons.collections4.functors.",
            "org.apache.xalan.",
            "org.codehaus.groovy.runtime.",
            "com.sun.",
            "net.sf.ehcache.",
            "org.mybatis.",
            "ch.qos.logback.",
            "org.apache.ibatis.",
    };

    /**
     * Built-in allow list prefixes. These classes are always allowed to be deserialized.
     */
    private static final String[] DEFAULT_ALLOW_PREFIXES = {
            "org.hongxi.jaws.",
            "java.lang.",
            "java.util.",
            "java.math.",
            "java.time.",
            "java.io.",
            "java.nio.",
    };

    private volatile CheckStatus checkStatus;

    private final Set<String> denyPrefixes;

    private final Set<String> allowPrefixes;

    private final Map<String, Class<?>> classCache = new ConcurrentHashMap<>(32);

    private final Set<String> warnedClasses = new CopyOnWriteArraySet<>();

    public Fastjson2SecurityFilter() {
        this(CheckStatus.WARN, DEFAULT_ALLOW_PREFIXES, DEFAULT_DENY_PREFIXES);
    }

    public Fastjson2SecurityFilter(CheckStatus checkStatus, String[] allowPrefixes, String[] denyPrefixes) {
        super(true, allowPrefixes);
        this.checkStatus = checkStatus;
        this.allowPrefixes = new CopyOnWriteArraySet<>(Arrays.asList(allowPrefixes));
        this.denyPrefixes = new CopyOnWriteArraySet<>(Arrays.asList(denyPrefixes));
    }

    @Override
    public Class<?> apply(String typeName, Class<?> expectClass, long features) {
        // 1. Call parent to check allow list (acceptNames), return if matched
        Class<?> tryLoad = super.apply(typeName, expectClass, features);
        if (tryLoad != null) {
            return tryLoad;
        }

        // 2. Check if the class is in the deny list
        if (isDenied(typeName)) {
            String msg = "[Fastjson2 Security] Deserialized class " + typeName
                    + " is in deny list, deserialization is not allowed.";
            log.warn(msg);
            throw new IllegalArgumentException(msg);
        }

        // 3. In STRICT mode, classes not in the allow list are rejected directly
        if (checkStatus == CheckStatus.STRICT) {
            String msg = "[Fastjson2 Security] Serialized class " + typeName
                    + " is not in allow list. Current mode is STRICT, deserialization is denied by default. "
                    + "Please add it to the allow list via Fastjson2SecurityFilter.addAllowPrefix().";
            if (warnedClasses.add(typeName)) {
                log.error(msg);
            }
            throw new IllegalArgumentException(msg);
        }

        // 4. WARN mode: try to load the class
        Class<?> localClass = loadClassDirectly(typeName);
        if (localClass != null) {
            if (warnedClasses.add(typeName)) {
                log.warn("[Fastjson2 Security] Serialized class {} is not in allow list. "
                        + "Current mode is WARN, deserialization is allowed by default. "
                        + "It is recommended to add it to the allow list or switch to STRICT mode.",
                        localClass.getName());
            }
            return localClass;
        }

        // 5. Class not found
        return null;
    }

    /**
     * Checks whether the given class name matches any prefix in the deny list.
     */
    private boolean isDenied(String typeName) {
        for (String denyPrefix : denyPrefixes) {
            if (typeName.startsWith(denyPrefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Loads a class directly, preferring the cache.
     */
    private Class<?> loadClassDirectly(String typeName) {
        Class<?> clazz = classCache.get(typeName);
        if (clazz != null) {
            return clazz;
        }

        clazz = TypeUtils.getMapping(typeName);
        if (clazz == null) {
            clazz = loadClass(typeName);
        }

        if (clazz != null) {
            classCache.putIfAbsent(typeName, clazz);
        }
        return clazz;
    }

    /**
     * Adds a prefix to the allow list.
     */
    public void addAllowPrefix(String prefix) {
        allowPrefixes.add(prefix);
    }

    /**
     * Adds a prefix to the deny list.
     */
    public void addDenyPrefix(String prefix) {
        denyPrefixes.add(prefix);
    }

    /**
     * Returns the current check mode.
     */
    public CheckStatus getCheckStatus() {
        return checkStatus;
    }

    /**
     * Sets the check mode.
     */
    public void setCheckStatus(CheckStatus checkStatus) {
        this.checkStatus = checkStatus;
    }
}
