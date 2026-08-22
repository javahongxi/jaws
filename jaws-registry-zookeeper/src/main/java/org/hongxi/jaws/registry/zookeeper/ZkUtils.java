package org.hongxi.jaws.registry.zookeeper;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.rpc.URL;

/**
 * Helpers that build the ZooKeeper node paths used by
 * {@link ZookeeperRegistry}: group path, service path, node-type path, and
 * the leaf node path (suffixed with {@code host:port}).
 * <p>
 * The hierarchy is {@code namespace/group/service/nodeType/host:port}, so
 * provider and consumer nodes of one service are separated by
 * {@link ZkNodeType}.
 *
 * @see ZkNodeType
 *
 * Created by shenhongxi on 2021/4/24.
 */
public class ZkUtils {

    public static String toGroupPath(URL url) {
        return JawsConstants.ZOOKEEPER_REGISTRY_NAMESPACE + JawsConstants.PATH_SEPARATOR + url.getGroup();
    }

    public static String toServicePath(URL url) {
        return toGroupPath(url) + JawsConstants.PATH_SEPARATOR + url.getPath();
    }

    public static String toNodeTypePath(URL url, ZkNodeType nodeType) {
        return toServicePath(url) + JawsConstants.PATH_SEPARATOR + nodeType.getValue();
    }

    public static String toNodePath(URL url, ZkNodeType nodeType) {
        return toNodeTypePath(url, nodeType) + JawsConstants.PATH_SEPARATOR + url.getHostPort();
    }
}