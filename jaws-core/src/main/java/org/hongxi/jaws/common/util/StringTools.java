package org.hongxi.jaws.common.util;

import org.apache.commons.lang3.StringUtils;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

public class StringTools {

    public static int parseInteger(String intStr) {
        if (intStr == null) {
            return 0;
        }
        try {
            return Integer.parseInt(intStr);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static String urlEncode(String value) {
        if (StringUtils.isEmpty(value)) {
            return "";
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public static String urlDecode(String value) {
        if (StringUtils.isBlank(value)) {
            return "";
        }
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    public static String toQueryString(Map<String, String> ps) {
        StringBuilder buf = new StringBuilder();
        if (ps != null && !ps.isEmpty()) {
            for (Map.Entry<String, String> entry : new TreeMap<>(ps).entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (key != null && !key.isEmpty() && value != null && !value.isEmpty()) {
                    if (buf.length() > 0) {
                        buf.append("&");
                    }
                    buf.append(key);
                    buf.append("=");
                    buf.append(value);
                }
            }
        }
        return buf.toString();
    }
}