package org.hongxi.jaws.registry.support;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.common.util.ConcurrentHashSet;
import org.hongxi.jaws.registry.NotifyListener;
import org.hongxi.jaws.registry.Registry;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.toggle.JawsToggleUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <pre>
 * Abstract registry。
 *
 * 对进出的url都进行createCopy保护，避免registry中的对象被修改，避免潜在的并发问题。
 *
 * </pre>
 * <p>
 * Created by shenhongxi on 2021/4/21.
 */
public abstract class AbstractRegistry implements Registry {

    private static final Logger log = LoggerFactory.getLogger(AbstractRegistry.class);

    protected String registryClassName = this.getClass().getSimpleName();
    private final ConcurrentHashMap<URL, Map<String, List<URL>>> subscribedCategoryResponses = new ConcurrentHashMap<>();
    private final URL registryUrl;
    private final Set<URL> registeredServiceUrls = new ConcurrentHashSet<>();

    public AbstractRegistry(URL url) {
        this.registryUrl = url.createCopy();

        // register a heartbeat toggle to perceive service state change and change available state
        JawsToggleUtils.initToggle(JawsConstants.REGISTRY_HEARTBEAT_TOGGLE, false);
        JawsToggleUtils.registerToggleListener(JawsConstants.REGISTRY_HEARTBEAT_TOGGLE, (key, value) -> {
            if (key != null && value != null) {
                if (value) {
                    available(null);
                } else {
                    unavailable(null);
                }
            }
        });
    }

    @Override
    public void register(URL url) {
        if (url == null) {
            log.warn("[{}] register with malformed param, url is null", registryClassName);
            return;
        }
        log.info("[{}] Url ({}) will register to Registry [{}]", registryClassName, url, registryUrl.getIdentity());
        doRegister(removeRegistryUnnecessaryParams(url.createCopy()));
        registeredServiceUrls.add(url);
        // available if heartbeat toggle already open
        if (JawsToggleUtils.isOpen(JawsConstants.REGISTRY_HEARTBEAT_TOGGLE)) {
            available(url);
        }
    }

    @Override
    public void unregister(URL url) {
        if (url == null) {
            log.warn("[{}] unregister with malformed param, url is null", registryClassName);
            return;
        }
        log.info("[{}] Url ({}) will unregister to Registry [{}]", registryClassName, url, registryUrl.getIdentity());
        doUnregister(removeRegistryUnnecessaryParams(url.createCopy()));
        registeredServiceUrls.remove(url);
    }

    @Override
    public void subscribe(URL url, NotifyListener listener) {
        if (url == null || listener == null) {
            log.warn("[{}] subscribe with malformed param, url:{}, listener:{}", registryClassName, url, listener);
            return;
        }
        log.info("[{}] Listener ({}) will subscribe to url ({}) in Registry [{}]", registryClassName, listener, url,
                registryUrl.getIdentity());
        doSubscribe(url.createCopy(), listener);
    }

    @Override
    public void unsubscribe(URL url, NotifyListener listener) {
        if (url == null || listener == null) {
            log.warn("[{}] unsubscribe with malformed param, url:{}, listener:{}", registryClassName, url, listener);
            return;
        }
        log.info("[{}] Listener ({}) will unsubscribe from url ({}) in Registry [{}]", registryClassName, listener, url,
                registryUrl.getIdentity());
        doUnsubscribe(url.createCopy(), listener);
    }

    @Override
    public List<URL> discover(URL url) {
        if (url == null) {
            log.warn("[{}] discover with malformed param, refUrl is null", registryClassName);
            return Collections.emptyList();
        }
        url = url.createCopy();
        List<URL> results = new ArrayList<>();

        Map<String, List<URL>> categoryUrls = subscribedCategoryResponses.get(url);
        if (categoryUrls != null && !categoryUrls.isEmpty()) {
            for (List<URL> urls : categoryUrls.values()) {
                for (URL tempUrl : urls) {
                    results.add(tempUrl.createCopy());
                }
            }
        } else {
            List<URL> urlsDiscovered = doDiscover(url);
            if (urlsDiscovered != null) {
                for (URL u : urlsDiscovered) {
                    results.add(u.createCopy());
                }
            }
        }
        return results;
    }

    @Override
    public URL getUrl() {
        return registryUrl;
    }

    @Override
    public Collection<URL> getRegisteredServiceUrls() {
        return registeredServiceUrls;
    }

    @Override
    public void available(URL url) {
        log.info("[{}] Url ({}) will set to available to Registry [{}]", registryClassName, url, registryUrl.getIdentity());
        if (url != null) {
            doAvailable(removeRegistryUnnecessaryParams(url.createCopy()));
        } else {
            doAvailable(null);
        }
    }

    @Override
    public void unavailable(URL url) {
        log.info("[{}] Url ({}) will set to unavailable to Registry [{}]", registryClassName, url, registryUrl.getIdentity());
        if (url != null) {
            doUnavailable(removeRegistryUnnecessaryParams(url.createCopy()));
        } else {
            doUnavailable(null);
        }
    }

    protected List<URL> getCachedUrls(URL url) {
        Map<String, List<URL>> rsUrls = subscribedCategoryResponses.get(url);
        if (rsUrls == null || rsUrls.isEmpty()) {
            return null;
        }

        List<URL> urls = new ArrayList<>();
        for (List<URL> us : rsUrls.values()) {
            for (URL tempUrl : us) {
                urls.add(tempUrl.createCopy());
            }
        }
        return urls;
    }

    protected void notify(URL refUrl, NotifyListener listener, List<URL> urls) {
        if (listener == null || urls == null) {
            return;
        }
        Map<String, List<URL>> nodeTypeUrlsInRs = new HashMap<>();
        for (URL surl : urls) {
            String nodeType = surl.getParameter(URLParamType.nodeType.getName(), URLParamType.nodeType.value());
            List<URL> oneNodeTypeUrls = nodeTypeUrlsInRs.get(nodeType);
            if (oneNodeTypeUrls == null) {
                nodeTypeUrlsInRs.put(nodeType, new ArrayList<>());
                oneNodeTypeUrls = nodeTypeUrlsInRs.get(nodeType);
            }
            oneNodeTypeUrls.add(surl);
        }
        Map<String, List<URL>> curls = subscribedCategoryResponses.get(refUrl);
        if (curls == null) {
            subscribedCategoryResponses.putIfAbsent(refUrl, new ConcurrentHashMap<>());
            curls = subscribedCategoryResponses.get(refUrl);
        }

        // refresh local urls cache
        curls.putAll(nodeTypeUrlsInRs);

        for (List<URL> us : nodeTypeUrlsInRs.values()) {
            listener.notify(getUrl(), us);
        }
    }

    /**
     * Remove parameters that are irrelevant to service discovery before interacting with the registry.
     * <p>
     * These fall into two categories:
     * <ul>
     *   <li>Provider-local settings (e.g. thread pool, server connections, codec, endpointFactory)
     *       that are transport/server concerns and not needed by consumers.</li>
     *   <li>Consumer-local settings (e.g. retries, loadBalance, cluster, haStrategy, check)
     *       that each consumer configures independently and should not be inherited from the provider.</li>
     * </ul>
     */
    private URL removeRegistryUnnecessaryParams(URL url) {
        // Transport SPI: codec is a local transport concern, consumer applies default on connect
        url.getParameters().remove(URLParamType.codec.getName());
        url.getParameters().remove(URLParamType.endpointFactory.getName());

        // Provider-local server settings
        url.getParameters().remove(URLParamType.maxServerConnections.getName());
        url.getParameters().remove(URLParamType.minWorkerThreads.getName());
        url.getParameters().remove(URLParamType.maxWorkerThreads.getName());
        url.getParameters().remove(URLParamType.workerQueueSize.getName());
        url.getParameters().remove(URLParamType.maxContentLength.getName());
        url.getParameters().remove(URLParamType.shareChannel.getName());
        url.getParameters().remove(URLParamType.accessLog.getName());

        // Provider-local client connection settings
        url.getParameters().remove(URLParamType.minClientConnections.getName());
        url.getParameters().remove(URLParamType.maxClientConnections.getName());
        url.getParameters().remove(URLParamType.maxConnectionsPerGroup.getName());

        // Consumer-local settings: each consumer configures these independently
        url.getParameters().remove(URLParamType.retries.getName());
        url.getParameters().remove(URLParamType.check.getName());
        url.getParameters().remove(URLParamType.throwException.getName());
        url.getParameters().remove(URLParamType.cluster.getName());
        url.getParameters().remove(URLParamType.loadBalance.getName());
        url.getParameters().remove(URLParamType.haStrategy.getName());
        url.getParameters().remove(URLParamType.requestTimeout.getName());
        url.getParameters().remove(URLParamType.connectTimeout.getName());
        url.getParameters().remove(URLParamType.filter.getName());
        url.getParameters().remove(URLParamType.fusingThreshold.getName());

        return url;
    }

    protected abstract void doRegister(URL url);

    protected abstract void doUnregister(URL url);

    protected abstract void doSubscribe(URL url, NotifyListener listener);

    protected abstract void doUnsubscribe(URL url, NotifyListener listener);

    protected abstract List<URL> doDiscover(URL url);

    protected abstract void doAvailable(URL url);

    protected abstract void doUnavailable(URL url);

}