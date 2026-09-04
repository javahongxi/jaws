package org.hongxi.jaws.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serial;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Base class for all RPC configuration beans.
 * <p>
 * Subclasses override {@link #collectParams(Map)} to explicitly declare which
 * Java Bean properties are exported into URL parameter maps.  This replaces
 * the former reflection-based mechanism so that the mapping is visible at a
 * glance and no getter can accidentally leak into the URL.
 *
 * @see #collectParams(Map)
 */
public class BaseConfig implements Serializable {
    @Serial
    private static final long serialVersionUID = 6221123514996466731L;

    private static final Logger log = LoggerFactory.getLogger(BaseConfig.class);

    /**
     * Suffixes to strip when deriving the config tag name (e.g. "ServiceConfig" -> "service").
     */
    private static final String[] SUFFIXES = new String[]{"Config", "Bean"};

    /**
     * The unique identifier of this config instance.
     */
    protected String id;

    // ---- Parameter collection ----

    /**
     * Collect config properties into the given parameter map.
     * <p>
     * Each subclass overrides this method to explicitly declare which of its
     * properties are exported as URL parameters.  Subclasses should call
     * {@code super.collectParams(params)} first so that inherited properties
     * are included.
     *
     * @param params the mutable parameter map to populate
     */
    protected void collectParams(Map<String, String> params) {
        // base class has no properties to export
    }

    /**
     * Helper for subclasses: put a non-null, non-blank value into the map.
     *
     * @param params target map
     * @param key    parameter key
     * @param value  property value (String, Number, or Boolean)
     */
    protected static void putIfPresent(Map<String, String> params, String key, Object value) {
        if (value == null) {
            return;
        }
        String s = String.valueOf(value).trim();
        if (!s.isEmpty()) {
            params.put(key, s);
        }
    }

    // ---- Legacy entry points (delegates to collectParams) ----

    /**
     * Append and override config parameters in order; later configs override earlier ones with the same key.
     *
     * @param parameters
     * @param configs
     */
    protected static void collectConfigParams(Map<String, String> parameters, BaseConfig... configs) {
        for (BaseConfig config : configs) {
            if (config != null) {
                config.appendConfigParams(parameters);
            }
        }
    }

    protected static void collectMethodConfigParams(Map<String, String> parameters, java.util.List<MethodConfig> methods) {
        if (methods == null || methods.isEmpty()) {
            return;
        }
        for (MethodConfig mc : methods) {
            if (mc != null) {
                String prefix = org.hongxi.jaws.common.JawsConstants.METHOD_CONFIG_PREFIX
                        + mc.getName() + "(" + mc.getArgumentTypes() + ")";
                mc.appendConfigParams(parameters, prefix);
            }
        }
    }

    protected void appendConfigParams(Map<String, String> parameters) {
        appendConfigParams(parameters, null);
    }

    /**
     * Append config properties into the given parameter map.
     *
     * @param parameters
     * @param prefix     optional key prefix (used for method-level configs)
     */
    protected void appendConfigParams(Map<String, String> parameters, String prefix) {
        Map<String, String> raw = new LinkedHashMap<>();
        collectParams(raw);
        if (prefix != null && !prefix.isEmpty()) {
            for (Map.Entry<String, String> entry : raw.entrySet()) {
                parameters.put(prefix + "." + entry.getKey(), entry.getValue());
            }
        } else {
            parameters.putAll(raw);
        }
    }

    // ---- id ----

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    // ---- toString ----

    @Override
    public String toString() {
        try {
            StringBuilder buf = new StringBuilder(getTagName(getClass()));
            Map<String, String> params = new LinkedHashMap<>();
            collectParams(params);
            for (Map.Entry<String, String> entry : params.entrySet()) {
                buf.append(' ').append(entry.getKey()).append('=').append(entry.getValue());
            }
            return buf.toString();
        } catch (Throwable t) {
            log.warn("Failed to build toString for config object", t);
            return super.toString();
        }
    }

    private static String getTagName(Class<?> cls) {
        String tag = cls.getSimpleName();
        for (String suffix : SUFFIXES) {
            if (tag.endsWith(suffix)) {
                tag = tag.substring(0, tag.length() - suffix.length());
                break;
            }
        }
        return tag.toLowerCase();
    }
}
