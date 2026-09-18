package org.hongxi.jaws.registry.harbor;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.rpc.URL;

/**
 * Mapping from a service URL to harbor's naming coordinates.
 * <p>
 * Names are identical to {@code NacosPathUtils} on purpose: during a migration the
 * two registry legs may point at the same HarborServer, and a provider registered as
 * {@code jaws/<interface>} must be findable by a consumer subscribed through the other
 * leg. Diverging here would not be a naming choice but a silent discovery failure.
 *
 * @author shenhongxi
 */
public final class HarborPathUtils {

    private HarborPathUtils() {
    }

    public static String toServiceName(URL url) {
        return JawsConstants.REGISTRY_SERVICE_NAMESPACE + JawsConstants.PATH_SEPARATOR + url.getPath();
    }

    public static String toGroup(URL url) {
        return url.getGroup();
    }
}
