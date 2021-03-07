package org.hongxi.jaws.common.util;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.rpc.URL;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by shenhongxi on 2021/3/5.
 */
public class UrlUtils {


    public static List<URL> parseURLs(String address, Map<String, String> defaults) {
        if (address == null || address.length() == 0) {
            return null;
        }
        String[] addresses = JawsConstants.REGISTRY_SPLIT_PATTERN.split(address);
        if (addresses == null || addresses.length == 0) {
            return null; // here won't be empty
        }
        List<URL> registries = new ArrayList<URL>();
        for (String addr : addresses) {
            registries.add(parseURL(addr, defaults));
        }
        return registries;
    }


    public static Map<String, String> parseQueryParams(String rawRefer) {
        Map<String, String> map = new HashMap<>();
        String refer = StringTools.urlDecode(rawRefer);
        String[] kvs = JawsConstants.QUERY_PARAM_PATTERN.split(refer);
        for (String kv : kvs) {
            if (kv != null && kv.contains(JawsConstants.EQUAL_SIGN_SEPARATOR)) {
                String[] kvArr = JawsConstants.EQUAL_SIGN_PATTERN.split(kv);
                if (kvArr.length == 2) {
                    map.put(kvArr[0].trim(), kvArr[1].trim());
                }
            }
        }
        return map;
    }

    private static URL parseURL(String address, Map<String, String> defaults) {
        if (address == null || address.length() == 0) {
            return null;
        }

        String[] addresses = JawsConstants.COMMA_SPLIT_PATTERN.split(address);
        String url = addresses[0];

        String defaultProtocol = defaults == null ? null : defaults.get("protocol");
        if (defaultProtocol == null || defaultProtocol.length() == 0) {
            defaultProtocol = URLParamType.protocol.value();
        }

        int defaultPort = StringTools.parseInteger(defaults == null ? null : defaults.get("port"));
        String defaultPath = defaults == null ? null : defaults.get("path");
        Map<String, String> defaultParameters = defaults == null ? null : new HashMap<String, String>(defaults);
        if (defaultParameters != null) {
            defaultParameters.remove("protocol");
            defaultParameters.remove("host");
            defaultParameters.remove("port");
            defaultParameters.remove("path");
        }
        URL u = URL.valueOf(url);
        u.addParameters(defaults);
        boolean changed = false;
        String protocol = u.getProtocol();
        String host = u.getHost();
        int port = u.getPort();
        String path = u.getPath();
        Map<String, String> parameters = new HashMap<String, String>(u.getParameters());
        if ((protocol == null || protocol.length() == 0) && defaultProtocol != null && defaultProtocol.length() > 0) {
            changed = true;
            protocol = defaultProtocol;
        }

        if (port <= 0) {
            if (defaultPort > 0) {
                changed = true;
                port = defaultPort;
            } else {
                changed = true;
                port = JawsConstants.DEFAULT_INT_VALUE;
            }
        }
        if (path == null || path.length() == 0) {
            if (defaultPath != null && defaultPath.length() > 0) {
                changed = true;
                path = defaultPath;
            }
        }
        if (defaultParameters != null && defaultParameters.size() > 0) {
            for (Map.Entry<String, String> entry : defaultParameters.entrySet()) {
                String key = entry.getKey();
                String defaultValue = entry.getValue();
                if (defaultValue != null && defaultValue.length() > 0) {
                    String value = parameters.get(key);
                    if (value == null || value.length() == 0) {
                        changed = true;
                        parameters.put(key, defaultValue);
                    }
                }
            }
        }
        if (changed) {
            u = new URL(protocol, host, port, path, parameters);
        }
        return u;
    }
}