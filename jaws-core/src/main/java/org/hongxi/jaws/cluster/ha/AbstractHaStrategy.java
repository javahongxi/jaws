package org.hongxi.jaws.cluster.ha;

import org.hongxi.jaws.cluster.HaStrategy;
import org.hongxi.jaws.rpc.URL;

/**
 * 
 * Abstract ha strategy.
 *
 * Created by shenhongxi on 2021/4/23.
 */

public abstract class AbstractHaStrategy<T> implements HaStrategy<T> {

    protected URL url;

    @Override
    public void setUrl(URL url) {
        this.url = url;
    }

}
