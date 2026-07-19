package org.hongxi.jaws.cluster.router;

import org.hongxi.jaws.cluster.AbstractRouter;
import org.hongxi.jaws.common.extension.SpiMeta;
import org.hongxi.jaws.rpc.URL;

/**
 * Routes requests to providers in a specific group.
 * <p>
 * When consumer url has a group set, only provider urls whose group matches
 * will be kept. If not set, all providers pass through.
 * <p>
 * Created by shenhongxi on 2025/7/19.
 */
@SpiMeta(name = "group")
public class GroupRouter extends AbstractRouter {

    @Override
    protected boolean match(URL providerUrl, URL consumerUrl) {
        String group = consumerUrl.getGroup();
        if (group == null || group.isEmpty()) {
            return true;
        }
        return group.equals(providerUrl.getGroup());
    }
}
