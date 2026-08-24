package org.hongxi.jaws.filter;

import org.hongxi.jaws.common.extension.Extension;
import org.hongxi.jaws.configcenter.DynamicConfiguration;
import org.hongxi.jaws.configcenter.DynamicConfigurationKeys;
import org.hongxi.jaws.configcenter.DynamicConfigurationUtils;
import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.URL;

import java.util.concurrent.Flow;

/**
 * A concrete reference node in the filter chain that wraps an original {@link Reference}
 * with a {@link Filter}. Each node delegates all interface methods to the original reference,
 * while the {@link #call(Request)} method applies the filter logic.
 * <p>
 * Supports dynamic filter toggle: if the filter is disabled via {@link DynamicConfiguration},
 * the call bypasses the filter and goes directly to the next reference in the chain.
 *
 * @param <T> service type
 */
class FilterReferenceWrapper<T> implements Reference<T> {

    private final Reference<T> original;
    private final Filter filter;
    private final String filterName;

    FilterReferenceWrapper(Reference<T> original, Filter filter) {
        this.original = original;
        this.filter = filter;
        this.filterName = resolveFilterName(filter);
    }

    @Override
    public Response call(Request request) {
        if (isFilterDisabled(request.getInterfaceName())) {
            return original.call(request);
        }
        return filter.filter(original, request);
    }

    @Override
    public Flow.Publisher<Object> callStream(Request request) {
        return original.callStream(request);
    }

    @Override
    public int activeReferenceCount() {
        return original.activeReferenceCount();
    }

    @Override
    public URL getServiceUrl() {
        return original.getServiceUrl();
    }

    @Override
    public Class<T> getInterface() {
        return original.getInterface();
    }

    @Override
    public URL getUrl() {
        return original.getUrl();
    }

    @Override
    public void init() {
        original.init();
    }

    @Override
    public void destroy() {
        original.destroy();
    }

    @Override
    public boolean isAvailable() {
        return original.isAvailable();
    }

    @Override
    public String desc() {
        return original.desc();
    }

    /**
     * Check if this filter is dynamically disabled via {@link DynamicConfiguration}.
     * Resolution order: service-level key -> global key. Enabled by default.
     */
    private boolean isFilterDisabled(String interfaceName) {
        if (filterName == null) {
            return false;
        }
        String val = DynamicConfigurationUtils.resolveStringConfig(
                DynamicConfigurationKeys.filterEnabled(filterName, interfaceName),
                DynamicConfigurationKeys.filterEnabled(filterName));
        return val != null && !"true".equalsIgnoreCase(val);
    }

    private static String resolveFilterName(Filter filter) {
        Extension ext = filter.getClass().getAnnotation(Extension.class);
        return ext != null ? ext.value() : null;
    }
}
