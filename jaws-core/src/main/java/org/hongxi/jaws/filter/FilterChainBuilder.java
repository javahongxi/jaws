package org.hongxi.jaws.filter;

import org.apache.commons.lang3.StringUtils;
import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.common.extension.ActivationComparator;
import org.hongxi.jaws.common.extension.ExtensionLoader;
import org.hongxi.jaws.common.extension.Extension;
import org.hongxi.jaws.rpc.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
        List<Filter> filters = loadFilters(url, JawsConstants.NODE_TYPE_SERVICE);
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
        List<Filter> filters = loadFilters(url, JawsConstants.NODE_TYPE_REFERENCE);
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
    private List<Filter> loadFilters(URL url, String key) {
        ExtensionLoader<Filter> loader = ExtensionLoader.getExtensionLoader(Filter.class);
        Set<String> filterNames = new LinkedHashSet<>(loader.getExtensionNames(key));

        // add filter names via "filter" config
        String filterStr = url.getParameter(URLParamType.filter.getName());
        if (StringUtils.isNotBlank(filterStr)) {
            String[] names = JawsConstants.COMMA_SPLIT_PATTERN.split(filterStr);
            Collections.addAll(filterNames, names);
        }

        // add filter names via other configs, like accessLog and so on
        if (url.getBoolParameter(URLParamType.accessLog)) {
            filterNames.add(AccessLogFilter.class.getAnnotation(Extension.class).value());
        }

        // load all filters by name
        List<Filter> filters = new ArrayList<>(filterNames.size());
        for (String name : filterNames) {
            Filter filter = loader.getExtension(name);
            if (filter != null) {
                filters.add(filter);
            }
        }

        // sort the filters
        filters.sort(new ActivationComparator<>());
        Collections.reverse(filters);
        return filters;
    }
}
