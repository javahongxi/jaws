package org.hongxi.jaws.config;

import org.apache.commons.lang3.StringUtils;
import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.util.MathUtils;
import org.hongxi.jaws.exception.JawsServiceException;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Config tools
 *
 * Created by shenhongxi on 2021/3/7.
 */

public class ConfigUtils {

    /**
     * export fomart: protocol1:port1,protocol2:port2
     * 
     * @param export
     * @return
     */
    public static Map<String, Integer> parseExport(String export) {
        if (StringUtils.isBlank(export)) {
            return Collections.emptyMap();
        }
        Map<String, Integer> pps = new HashMap<String, Integer>();
        String[] protocolAndPorts = JawsConstants.COMMA_SPLIT_PATTERN.split(export);
        for (String pp : protocolAndPorts) {
            if (StringUtils.isBlank(pp)) {
                continue;
            }
            String[] ppDetail = pp.split(":");
            if (ppDetail.length == 2) {
                pps.put(ppDetail[0], Integer.parseInt(ppDetail[1]));
            } else if (ppDetail.length == 1) {
                if (JawsConstants.PROTOCOL_INJVM.equals(ppDetail[0])) {
                    pps.put(ppDetail[0], JawsConstants.DEFAULT_INT_VALUE);
                } else {
                    int port = MathUtils.parseInt(ppDetail[0], 0);
                    if (port <= 0) {
                        throw new JawsServiceException("Export is malformed :" + export);
                    } else {
                        pps.put(JawsConstants.PROTOCOL_JAWS, port);
                    }
                }
            } else {
                throw new JawsServiceException("Export is malformed :" + export);
            }
        }
        return pps;
    }

    public static String extractProtocols(String export) {
        Map<String, Integer> protocols = parseExport(export);
        StringBuilder sb = new StringBuilder(16);
        for (String p : protocols.keySet()) {
            sb.append(p).append(JawsConstants.COMMA_SEPARATOR);
        }
        if (sb.length() > 0) {
            sb.deleteCharAt(sb.length() - 1);
        }
        return sb.toString();
    }
}
