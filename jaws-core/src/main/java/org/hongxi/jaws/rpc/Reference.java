package org.hongxi.jaws.rpc;

import org.hongxi.jaws.common.extension.Scope;
import org.hongxi.jaws.common.extension.Spi;

/**
 * Created by shenhongxi on 2021/4/21.
 */
@Spi(scope = Scope.PROTOTYPE)
public interface Reference<T> extends Caller<T>, Endpoint {

    /**
     * The number of active calls currently using this reference.
     *
     * @return active call count
     */
    int activeReferenceCount();

    /**
     * Get the original service URL of this reference.
     *
     * @return service URL
     */
    URL getServiceUrl();
}