package org.hongxi.jaws.common.util;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.rpc.URL;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Parses registry addresses into {@link URL} instances, splitting
 * multi-registry address strings and applying default protocol, port, path
 * and parameters to entries that omit them.
 * <p>
 * Comma-separated backup addresses are encoded into the {@code backup}
 * parameter so multi-node information is preserved in a single URL.
 * <p>
 * Created by shenhongxi on 2021/3/5.
 */
public class UrlUtils {

    public static List<URL> parseURLs(String address, Map<String, String> defaults) {
        if (address == null || address.isEmpty()) {
            return null;
        }
        String[] addresses = JawsConstants.REGISTRY_SPLIT_PATTERN.split(address);
        if (addresses == null || addresses.length == 0) {
            return null;
        }
        List<URL> registries = new ArrayList<>();
        for (String addr : addresses) {
            registries.add(parseURL(addr, defaults));
        }
        return registries;
    }

    private static URL parseURL(String address, Map<String, String> defaults) {
        if (address == null || address.isEmpty()) {
            return null;
        }

        String[] addresses = JawsConstants.COMMA_SPLIT_PATTERN.split(address);
        String url = addresses[0];
        // Encode comma-separated backup addresses into the backup parameter to preserve multi-node info
        if (addresses.length > 1) {
            StringJoiner backup = new StringJoiner(",");
            for (int i = 1; i < addresses.length; i++) {
                backup.add(addresses[i]);
            }
            url += "?" + URL.BACKUP_KEY + "=" + backup;
        }

        String defaultProtocol = defaults == null ? null : defaults.get("protocol");
        if (defaultProtocol == null || defaultProtocol.isEmpty()) {
            defaultProtocol = UrlParam.Transport.PROTOCOL.value();
        }

        int defaultPort = parseIntOrDefault0(defaults == null ? null : defaults.get("port"));
        String defaultPath = defaults == null ? null : defaults.get("path");

        // Extract default parameters excluding reserved keys
        Map<String, String> defaultParameters = new HashMap<>();
        if (defaults != null) {
            for (Map.Entry<String, String> entry : defaults.entrySet()) {
                String key = entry.getKey();
                if (!"protocol".equals(key) && !"host".equals(key)
                        && !"port".equals(key) && !"path".equals(key)) {
                    defaultParameters.put(key, entry.getValue());
                }
            }
        }

        URL u = URL.valueOf(url);
        boolean changed = false;
        String protocol = u.getProtocol();
        String host = u.getHost();
        int port = u.getPort();
        String path = u.getPath();
        Map<String, String> parameters = new HashMap<>(u.getParameters());

        if (protocol == null || protocol.isEmpty()) {
            changed = true;
            protocol = defaultProtocol;
        }

        if (port <= 0) {
            changed = true;
            port = Math.max(defaultPort, 0);
        }

        if ((path == null || path.isEmpty()) && defaultPath != null && !defaultPath.isEmpty()) {
            changed = true;
            path = defaultPath;
        }

        // Merge default parameters (only fill in missing keys)
        if (!defaultParameters.isEmpty()) {
            for (Map.Entry<String, String> entry : defaultParameters.entrySet()) {
                String key = entry.getKey();
                String defaultValue = entry.getValue();
                if (defaultValue != null && !defaultValue.isEmpty() && !parameters.containsKey(key)) {
                    changed = true;
                    parameters.put(key, defaultValue);
                }
            }
        }

        if (changed) {
            u = new URL(protocol, host, port, path, parameters);
        }
        return u;
    }

    /** Lenient integer parsing that falls back to 0 for null or invalid input. */
    private static int parseIntOrDefault0(String value) {
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
