package org.hongxi.jaws.protocol;

import org.apache.commons.lang3.StringUtils;
import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.common.extension.ActivationComparator;
import org.hongxi.jaws.common.extension.ExtensionLoader;
import org.hongxi.jaws.common.extension.SpiMeta;
import org.hongxi.jaws.filter.AccessLogFilter;
import org.hongxi.jaws.filter.Filter;
import org.hongxi.jaws.filter.InitializableFilter;
import org.hongxi.jaws.rpc.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds filter chains for both {@link Provider} and {@link Reference}.
 * <p>
 * Responsibilities:
 * <ul>
 *     <li>Load and sort filters via SPI and URL configuration</li>
 *     <li>Build provider-side filter chain using {@link FilterProviderWrapper}</li>
 *     <li>Build reference-side filter chain using {@link FilterReferenceWrapper}</li>
 * </ul>
 */
class FilterChainBuilder {

    /**
     * Build a filter chain for the given provider.
     *
     * @param provider the original service provider
     * @param url      the service URL
     * @param <T>      service type
     * @return the provider wrapped with the filter chain
     */
    <T> Provider<T> buildProviderChain(Provider<T> provider, URL url) {
        List<Filter> filters = getFilters(url, JawsConstants.NODE_TYPE_SERVICE);
        if (filters.isEmpty()) {
            return provider;
        }
        Provider<T> last = provider;
        for (Filter filter : filters) {
            if (filter instanceof InitializableFilter initFilter) {
                initFilter.init(last);
            }
            last = new FilterProviderWrapper<>(last, filter);
        }
        return last;
    }

    /**
     * Build a filter chain for the given reference.
     *
     * @param reference the original service reference
     * @param url       the service URL
     * @param <T>       service type
     * @return the reference wrapped with the filter chain
     */
    <T> Reference<T> buildReferenceChain(Reference<T> reference, URL url) {
        List<Filter> filters = getFilters(url, JawsConstants.NODE_TYPE_REFERENCE);
        if (filters.isEmpty()) {
            return reference;
        }
        Reference<T> last = reference;
        for (Filter filter : filters) {
            if (filter instanceof InitializableFilter initFilter) {
                initFilter.init(last);
            }
            last = new FilterReferenceWrapper<>(last, filter);
        }
        return last;
    }

    /**
     * Load filters from SPI defaults, URL "filter" parameter, and other config triggers.
     */
    private List<Filter> getFilters(URL url, String key) {
        List<Filter> filters = new ArrayList<>();

        // load default filters
        List<Filter> defaultFilters = ExtensionLoader.getExtensionLoader(Filter.class).getExtensions(key);
        if (!defaultFilters.isEmpty()) {
            filters.addAll(defaultFilters);
        }

        // add filters via "filter" config
        String filterStr = url.getParameter(URLParamType.filter.getName());
        if (StringUtils.isNotBlank(filterStr)) {
            String[] filterNames = JawsConstants.COMMA_SPLIT_PATTERN.split(filterStr);
            for (String fn : filterNames) {
                addIfAbsent(filters, fn);
            }
        }

        // add filter via other configs, like accessLog and so on
        if (url.getBoolParameter(URLParamType.accessLog)) {
            addIfAbsent(filters, AccessLogFilter.class.getAnnotation(SpiMeta.class).name());
        }

        // sort the filters
        filters.sort(new ActivationComparator<>());
        Collections.reverse(filters);
        return filters;
    }

    private void addIfAbsent(List<Filter> filters, String extensionName) {
        Filter extFilter = ExtensionLoader.getExtensionLoader(Filter.class).getExtension(extensionName);
        if (extFilter == null) {
            return;
        }
        boolean exists = false;
        for (Filter f : filters) {
            if (f.getClass() == extFilter.getClass()) {
                exists = true;
                break;
            }
        }
        if (!exists) {
            filters.add(extFilter);
        }
    }
}