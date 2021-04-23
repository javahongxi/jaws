package org.hongxi.jaws.registry.zookeeper;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.rpc.URL;

/**
 * Created by shenhongxi on 2021/4/24.
 */
public class ZkUtils {

    public static String toGroupPath(URL url) {
        return JawsConstants.ZOOKEEPER_REGISTRY_NAMESPACE + JawsConstants.PATH_SEPARATOR + url.getGroup();
    }

    public static String toServicePath(URL url) {
        return toGroupPath(url) + JawsConstants.PATH_SEPARATOR + url.getPath();
    }

    public static String toCommandPath(URL url) {
        return toGroupPath(url) + JawsConstants.ZOOKEEPER_REGISTRY_COMMAND;
    }

    public static String toNodeTypePath(URL url, ZkNodeType nodeType) {
        return toServicePath(url) + JawsConstants.PATH_SEPARATOR + nodeType.getValue();
    }

    public static String toNodePath(URL url, ZkNodeType nodeType) {
        return toNodeTypePath(url, nodeType) + JawsConstants.PATH_SEPARATOR + url.getServerPortStr();
    }
}