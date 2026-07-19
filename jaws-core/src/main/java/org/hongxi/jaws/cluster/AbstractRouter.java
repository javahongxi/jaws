package org.hongxi.jaws.cluster;

import org.hongxi.jaws.rpc.URL;

import java.util.ArrayList;
import java.util.List;

/**
 * Abstract router with template method pattern.
 * Subclasses only need to implement the match condition.
 * <p>
 * Created by shenhongxi on 2025/7/19.
 */
public abstract class AbstractRouter implements Router {

    @Override
    public List<URL> route(List<URL> urls, URL consumerUrl) {
        if (urls == null || urls.isEmpty()) {
            return urls;
        }
        List<URL> result = new ArrayList<>(urls.size());
        for (URL url : urls) {
            if (match(url, consumerUrl)) {
                result.add(url);
            }
        }
        // if all urls are filtered out, return original list to avoid no available provider
        return result.isEmpty() ? urls : result;
    }

    /**
     * Check if the provider url matches the routing condition.
     *
     * @param providerUrl provider url
     * @param consumerUrl consumer url with routing conditions
     * @return true if the provider url should be kept
     */
    protected abstract boolean match(URL providerUrl, URL consumerUrl);
}
