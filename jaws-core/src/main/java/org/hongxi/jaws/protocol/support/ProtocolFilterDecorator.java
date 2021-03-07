package org.hongxi.jaws.protocol.support;

import org.apache.commons.lang3.StringUtils;
import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.common.extension.ActivationComparator;
import org.hongxi.jaws.common.extension.ExtensionLoader;
import org.hongxi.jaws.common.extension.SpiMeta;
import org.hongxi.jaws.exception.JawsErrorMsgConstants;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.filter.AccessLogFilter;
import org.hongxi.jaws.filter.Filter;
import org.hongxi.jaws.filter.InitializableFilter;
import org.hongxi.jaws.rpc.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 *
 * Decorate the protocol, to add more features.
 *
 * Created by shenhongxi on 2021/3/6.
 */

public class ProtocolFilterDecorator implements Protocol {

    private Protocol protocol;

    public ProtocolFilterDecorator(Protocol protocol) {
        if (protocol == null) {
            throw new JawsFrameworkException("Protocol is null when construct ProtocolFilterDecorator",
                    JawsErrorMsgConstants.FRAMEWORK_INIT_ERROR);
        }
        this.protocol = protocol;
    }

    @Override
    public <T> Exporter<T> export(Provider<T> provider, URL url) {
        return protocol.export(decorateWithFilter(provider, url), url);
    }

    @Override
    public void destroy() {
        protocol.destroy();
    }

    private <T> Provider<T> decorateWithFilter(final Provider<T> provider, URL url) {
        List<Filter> filters = getFilters(url, JawsConstants.NODE_TYPE_SERVICE);
        if (filters.size() == 0) {
            return provider;
        }
        Provider<T> lastProvider = provider;
        for (Filter filter : filters) {
            final Filter f = filter;
            if (f instanceof InitializableFilter) {
                ((InitializableFilter) f).init(lastProvider);
            }
            final Provider<T> lp = lastProvider;
            lastProvider = new Provider<T>() {
                @Override
                public Response call(Request request) {
                    return f.filter(lp, request);
                }

                @Override
                public String desc() {
                    return lp.desc();
                }

                @Override
                public void destroy() {
                    lp.destroy();
                }

                @Override
                public Class<T> getInterface() {
                    return lp.getInterface();
                }

                @Override
                public Method lookupMethod(String methodName, String methodDesc) {
                    return lp.lookupMethod(methodName, methodDesc);
                }

                @Override
                public URL getUrl() {
                    return lp.getUrl();
                }

                @Override
                public void init() {
                    lp.init();
                }

                @Override
                public boolean isAvailable() {
                    return lp.isAvailable();
                }

				@Override
				public T getImpl() {
					return provider.getImpl();
				}
            };
        }
        return lastProvider;
    }

    /**
     * <pre>
	 * 获取方式：
	 * 1）先获取默认的filter列表；
	 * 2）根据filter配置获取新的filters，并和默认的filter列表合并；
	 * 3）再根据一些其他配置判断是否需要增加其他filter，如根据accessLog进行判断，是否需要增加accesslog
	 * </pre>
     *
     * @param url
     * @param key
     * @return
     */
    private List<Filter> getFilters(URL url, String key) {

        // load default filters
        List<Filter> filters = new ArrayList<>();
        List<Filter> defaultFilters = ExtensionLoader.getExtensionLoader(Filter.class).getExtensions(key);
        if (defaultFilters != null && defaultFilters.size() > 0) {
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
        boolean accessLog = url.getBooleanParameter(URLParamType.accessLog.getName(), URLParamType.accessLog.boolValue());
        if (accessLog) {
            addIfAbsent(filters, AccessLogFilter.class.getAnnotation(SpiMeta.class).name());
        }

        // sort the filters
        filters.sort(new ActivationComparator<>());
        Collections.reverse(filters);
        return filters;
    }

    private void addIfAbsent(List<Filter> filters, String extensionName) {
        if (StringUtils.isBlank(extensionName)) {
            return;
        }

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