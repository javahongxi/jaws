package org.hongxi.jaws.serialize;

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
 * Fastjson2 反序列化安全过滤器。
 * <p>
 * 参考 Dubbo 的 Fastjson2SecurityManager，通过白名单/黑名单机制控制反序列化时的
 * AutoType 类加载，防止恶意类注入攻击。
 * <p>
 * 支持两种检查模式：
 * <ul>
 *   <li>{@link CheckStatus#STRICT} - 严格模式，不在白名单中的类直接拒绝反序列化</li>
 *   <li>{@link CheckStatus#WARN} - 警告模式，不在白名单中的类允许反序列化但记录警告日志</li>
 * </ul>
 */
public class Fastjson2SecurityFilter extends ContextAutoTypeBeforeHandler {

    private static final Logger log = LoggerFactory.getLogger(Fastjson2SecurityFilter.class);

    /**
     * 安全检查模式
     */
    public enum CheckStatus {
        /**
         * 严格模式：不在白名单中的类直接拒绝反序列化
         */
        STRICT,
        /**
         * 警告模式：不在白名单中的类允许反序列化，但记录警告日志
         */
        WARN
    }

    /**
     * 内置黑名单前缀，这些类永远不允许反序列化
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
     * 内置白名单前缀，这些类始终允许反序列化
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
        // 1. 先调用父类检查白名单（acceptNames），命中则直接返回
        Class<?> tryLoad = super.apply(typeName, expectClass, features);
        if (tryLoad != null) {
            return tryLoad;
        }

        // 2. 检查是否在黑名单中
        if (isDenied(typeName)) {
            String msg = "[Fastjson2 Security] Deserialized class " + typeName
                    + " is in deny list, deserialization is not allowed.";
            log.warn(msg);
            throw new IllegalArgumentException(msg);
        }

        // 3. 严格模式下，不在白名单中的类直接拒绝
        if (checkStatus == CheckStatus.STRICT) {
            String msg = "[Fastjson2 Security] Serialized class " + typeName
                    + " is not in allow list. Current mode is STRICT, deserialization is denied by default. "
                    + "Please add it to the allow list via Fastjson2SecurityFilter.addAllowPrefix().";
            if (warnedClasses.add(typeName)) {
                log.error(msg);
            }
            throw new IllegalArgumentException(msg);
        }

        // 4. WARN 模式：尝试加载类
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

        // 5. 类未找到
        return null;
    }

    /**
     * 检查类名是否匹配黑名单中的任一前缀
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
     * 直接加载类，优先从缓存获取
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
     * 添加白名单前缀
     */
    public void addAllowPrefix(String prefix) {
        allowPrefixes.add(prefix);
    }

    /**
     * 添加黑名单前缀
     */
    public void addDenyPrefix(String prefix) {
        denyPrefixes.add(prefix);
    }

    /**
     * 获取当前检查模式
     */
    public CheckStatus getCheckStatus() {
        return checkStatus;
    }

    /**
     * 设置检查模式
     */
    public void setCheckStatus(CheckStatus checkStatus) {
        this.checkStatus = checkStatus;
    }
}
