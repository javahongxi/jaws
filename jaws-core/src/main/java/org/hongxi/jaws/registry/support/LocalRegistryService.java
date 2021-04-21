package org.hongxi.jaws.registry.support;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.common.extension.SpiMeta;
import org.hongxi.jaws.common.util.CollectionUtils;
import org.hongxi.jaws.common.util.ConcurrentHashSet;
import org.hongxi.jaws.common.util.NetUtils;
import org.hongxi.jaws.registry.NotifyListener;
import org.hongxi.jaws.registry.RegistryService;
import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@SpiMeta(name = "local")
public class LocalRegistryService extends AbstractRegistry {
    
    private static final Logger log = LoggerFactory.getLogger(LocalRegistryService.class);

    /** Map<interface/nodeType, List<URL>>, List 中的url用identity/id来区分唯一性 */
    private ConcurrentMap<String, List<URL>> registeredServices = new ConcurrentHashMap<>();

    private ConcurrentHashMap<String, ConcurrentHashMap<URL, ConcurrentHashSet<NotifyListener>>> subscribeListeners =
            new ConcurrentHashMap<>();
    private URL registryUrl;

    public LocalRegistryService() {
        this(new URL(JawsConstants.REGISTRY_PROTOCOL_LOCAL, NetUtils.LOCALHOST, JawsConstants.DEFAULT_INT_VALUE,
                RegistryService.class.getName()));
    }

    public LocalRegistryService(URL url) {
        super(url);
        this.registryUrl = url;
    }

    @Override
    public void doSubscribe(URL url, NotifyListener listener) {

        String subscribeKey = getSubscribeKey(url);
        ConcurrentHashMap<URL, ConcurrentHashSet<NotifyListener>> urlListeners = subscribeListeners.get(subscribeKey);
        if (urlListeners == null) {
            subscribeListeners.putIfAbsent(subscribeKey, new ConcurrentHashMap<URL, ConcurrentHashSet<NotifyListener>>());
            urlListeners = subscribeListeners.get(subscribeKey);
        }

        ConcurrentHashSet<NotifyListener> listeners = urlListeners.get(url);
        if (listeners == null) {
            urlListeners.putIfAbsent(url, new ConcurrentHashSet<NotifyListener>());
            listeners = urlListeners.get(url);
        }

        listeners.add(listener);

        List<URL> urls = discover(url);
        if (urls != null && urls.size() > 0) {
            listener.notify(getUrl(), urls);
        }

        log.info("LocalRegistryService subscribe: url={}", url);
    }

    @Override
    public void doUnsubscribe(URL url, NotifyListener listener) {
        String subscribeKey = getSubscribeKey(url);
        ConcurrentHashMap<URL, ConcurrentHashSet<NotifyListener>> urlListeners = subscribeListeners.get(subscribeKey);
        if (urlListeners != null) {
            urlListeners.remove(url);
        }

        log.info("LocalRegistryService unsubscribe: url={}", url);
    }

    @Override
    public List<URL> doDiscover(URL url) {
        return registeredServices.get(getRegistryKey(url));
    }

    @Override
    protected void doAvailable(URL url) {
        //do nothing
    }

    @Override
    protected void doUnavailable(URL url) {
        //do nothing
    }

    @Override
    public void doRegister(URL url) {
        String registryKey = getRegistryKey(url);
        synchronized (registeredServices) {
            List<URL> urls = registeredServices.get(registryKey);

            if (urls == null) {
                registeredServices.putIfAbsent(registryKey, new ArrayList<URL>());
                urls = registeredServices.get(registryKey);
            }
            add(url, urls);

            log.info("LocalRegistryService register: url={}", url);

            notifyListeners(url);
        }
    }

    @Override
    public void doUnregister(URL url) {
        synchronized (registeredServices) {
            List<URL> urls = registeredServices.get(getRegistryKey(url));

            if (urls == null) {
                return;
            }

            remove(url, urls);

            log.info("LocalRegistryService unregister: url={}", url);
            // 在变更后立即进行通知
            notifyListeners(url);
        }
    }

    @Override
    public URL getUrl() {
        return registryUrl;
    }

    /**
     * 防止数据在外部被变更，因此copy一份
     * 
     * @return
     */
    public Map<String, List<URL>> getAllUrl() {
        Map<String, List<URL>> copyMap = new HashMap<String, List<URL>>(registeredServices.size());

        for (Map.Entry<String, List<URL>> entry : registeredServices.entrySet()) {
            String key = entry.getKey();

            List<URL> copyList = new ArrayList<URL>(entry.getValue().size());
            for (URL url : entry.getValue()) {
                copyList.add(url.createCopy());
            }

            copyMap.put(key, copyList);
        }

        return copyMap;
    }

    private void remove(URL url, List<URL> urls) {
        if (CollectionUtils.isEmpty(urls)) {
            return;
        }
        removeCachedUrlByIdentity(url, urls);
    }

    private void add(URL url, List<URL> urls) {
        removeCachedUrlByIdentity(url, urls);
        urls.add(url);
    }

    private void removeCachedUrlByIdentity(URL url, List<URL> urls) {
        if (CollectionUtils.isEmpty(urls)) {
            return;
        }
        URL oldUrl = null;
        for (URL cachedUrl : urls) {
            if (Objects.equals(url, cachedUrl)) {
                oldUrl = cachedUrl;
                break;
            }
        }

        if (oldUrl != null) {
            urls.remove(oldUrl);
        }
    }

    private void notifyListeners(URL changedUrl) {
        List<URL> interestingUrls = discover(changedUrl);
        if (interestingUrls != null) {
            ConcurrentHashMap<URL, ConcurrentHashSet<NotifyListener>> urlListeners = subscribeListeners.get(getSubscribeKey(changedUrl));
            if (urlListeners == null) {
                return;
            }

            for (ConcurrentHashSet<NotifyListener> listeners : urlListeners.values()) {
                for (NotifyListener ln : listeners) {
                    try {
                        ln.notify(getUrl(), interestingUrls);
                    } catch (Exception e) {
                        log.warn(String.format("Exception when notify listerner %s, changedUrl: %s", ln, changedUrl), e);
                    }
                }
            }

        }
    }

    private String getRegistryKey(URL url) {
        String keyPrefix = url.getPath();
        String nodeType = url.getParameter(URLParamType.nodeType.getName());
        if (nodeType != null) {
            return keyPrefix + JawsConstants.PATH_SEPARATOR + nodeType;
        } else {
            log.warn("Url need a nodeType as param in localRegistry, url={}", url);
            return keyPrefix;
        }
    }

    private String getSubscribeKey(URL url) {
        return getRegistryKey(url);
    }
}