package org.hongxi.jaws.registry.support.command;

import org.apache.commons.lang3.StringUtils;
import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.common.util.CollectionUtils;
import org.hongxi.jaws.common.util.ConcurrentHashSet;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.registry.NotifyListener;
import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages service discovery and command-based cross-group merging.
 * <p>
 * This class subscribes to both service list changes and command configuration changes.
 * When either changes, it recomputes the final service URL list by merging groups
 * according to the current command configuration.
 * <p>
 * Note: IP-based route rules are NOT handled here. They are delegated to
 * {@link CommandRouter} which evaluates them on each RPC call.
 */
public class CommandServiceManager implements CommandListener, ServiceListener {

    private static final Logger log = LoggerFactory.getLogger(CommandServiceManager.class);

    private final URL refUrl;
    private final ConcurrentHashSet<NotifyListener> notifySet;
    private CommandFailbackRegistry registry;

    /**
     * Service cache keyed by group name.
     */
    private final Map<String, List<URL>> groupServiceCache;

    /**
     * Raw command string cache for change detection.
     */
    private volatile String commandStringCache = "";

    /**
     * Parsed command cache. Updated atomically under lock.
     */
    private volatile RpcCommand commandCache;

    public CommandServiceManager(URL refUrl) {
        log.info("CommandServiceManager init url:{}", refUrl.toFullStr());
        this.refUrl = refUrl;
        this.notifySet = new ConcurrentHashSet<>();
        this.groupServiceCache = new ConcurrentHashMap<>();
    }

    @Override
    public synchronized void notifyService(URL serviceUrl, URL registryUrl, List<URL> urls) {
        if (registry == null) {
            throw new JawsFrameworkException("registry must be set.");
        }

        URL urlCopy = serviceUrl.createCopy();
        String groupName = urlCopy.getParameter(URLParamType.group.getName(), URLParamType.group.value());
        groupServiceCache.put(groupName, urls);

        List<URL> finalResult = computeFinalResult();

        for (NotifyListener notifyListener : notifySet) {
            notifyListener.notify(registry.getUrl(), finalResult);
        }
    }

    @Override
    public synchronized void notifyCommand(URL serviceUrl, String commandString) {
        log.info("CommandServiceManager notify command. service:{}, command:{}", serviceUrl.toSimpleString(), commandString);

        if (commandString == null) {
            commandString = "";
        }

        if (StringUtils.equals(commandString, commandStringCache)) {
            log.info("command not change. url:{}", serviceUrl.toSimpleString());
            return;
        }

        commandStringCache = commandString;
        commandCache = RpcCommandUtils.stringToCommand(commandStringCache);

        if (commandCache != null && commandCache.getClientCommands() != null && !commandCache.getClientCommands().isEmpty()) {
            commandCache.sort();
        } else if (StringUtils.isNotBlank(commandString)) {
            // parse failure, fall back to no-command mode
            log.warn("command parse fail, ignored! command:{}", commandString);
            commandStringCache = "";
            commandCache = null;
        }

        // clean up group caches for groups no longer referenced by the command
        cleanupStaleGroups();

        // if command is cleared or has no merge groups, re-subscribe to the original group
        if (commandCache == null || CollectionUtils.isEmpty(commandCache.getClientCommands())) {
            log.info("reSub service {}", refUrl.toSimpleString());
            registry.subscribeService(refUrl, this);
        }

        List<URL> finalResult = computeFinalResult();

        for (NotifyListener notifyListener : notifySet) {
            notifyListener.notify(registry.getUrl(), finalResult);
        }
    }

    /**
     * Compute the final URL list based on the current command and group caches.
     * Evaluates ALL matching client commands (not just the first one) and merges
     * their group results.
     */
    private List<URL> computeFinalResult() {
        RpcCommand currentCommand = this.commandCache;
        if (currentCommand == null || CollectionUtils.isEmpty(currentCommand.getClientCommands())) {
            return discoverOneGroup(refUrl);
        }

        String path = refUrl.getPath();
        List<URL> mergedResult = null;
        boolean hit = false;

        for (RpcCommand.ClientCommand command : currentCommand.getClientCommands()) {
            if (!RpcCommandUtils.match(command.getPattern(), path)) {
                continue;
            }
            hit = true;

            if (!CollectionUtils.isEmpty(command.getMergeGroups())) {
                Map<String, Integer> weights = new HashMap<>();
                try {
                    buildWeightsMap(weights, command);
                } catch (JawsFrameworkException e) {
                    log.warn("build weights map fail! {}", e.getMessage());
                    continue;
                }
                List<URL> groupResult = mergeResult(refUrl, weights);
                if (mergedResult == null) {
                    mergedResult = groupResult;
                } else {
                    mergedResult.addAll(groupResult);
                }
            } else {
                List<URL> groupResult = discoverOneGroup(refUrl);
                if (mergedResult == null) {
                    mergedResult = new ArrayList<>(groupResult);
                } else {
                    mergedResult.addAll(groupResult);
                }
            }
        }

        if (!hit || mergedResult == null) {
            return discoverOneGroup(refUrl);
        }

        log.info("mergedResult: size-{} --- {}", mergedResult.size(), mergedResult);
        return mergedResult;
    }

    /**
     * Remove group caches and unsubscribe for groups no longer referenced by the current command.
     */
    private void cleanupStaleGroups() {
        Set<String> activeGroups = collectActiveGroups();

        Set<String> groupKeys = new HashSet<>(groupServiceCache.keySet());
        for (String gk : groupKeys) {
            if (!activeGroups.contains(gk)) {
                groupServiceCache.remove(gk);
                URL urlTemp = refUrl.createCopy();
                urlTemp.addParameter(URLParamType.group.getName(), gk);
                registry.unsubscribeService(urlTemp, this);
            }
        }
    }

    /**
     * Collect all group names referenced by the current command plus the original group.
     */
    private Set<String> collectActiveGroups() {
        Set<String> activeGroups = new HashSet<>();
        String defaultGroup = refUrl.getParameter(URLParamType.group.getName(), URLParamType.group.value());
        activeGroups.add(defaultGroup);

        RpcCommand currentCommand = this.commandCache;
        if (currentCommand != null && !CollectionUtils.isEmpty(currentCommand.getClientCommands())) {
            String path = refUrl.getPath();
            for (RpcCommand.ClientCommand cmd : currentCommand.getClientCommands()) {
                if (RpcCommandUtils.match(cmd.getPattern(), path) && !CollectionUtils.isEmpty(cmd.getMergeGroups())) {
                    for (String rule : cmd.getMergeGroups()) {
                        String[] gw = rule.split(":");
                        activeGroups.add(gw[0]);
                    }
                }
            }
        }
        return activeGroups;
    }

    /**
     * Discover the merged service list for a command, subscribing to new groups as needed.
     * Route rules are NOT applied here; they are handled by {@link CommandRouter}.
     */
    public List<URL> discoverServiceWithCommand(URL serviceUrl, Map<String, Integer> weights, RpcCommand rpcCommand) {
        if (rpcCommand == null || CollectionUtils.isEmpty(rpcCommand.getClientCommands())) {
            return discoverOneGroup(serviceUrl);
        }

        String path = serviceUrl.getPath();
        List<URL> mergedResult = null;
        boolean hit = false;

        for (RpcCommand.ClientCommand command : rpcCommand.getClientCommands()) {
            if (!RpcCommandUtils.match(command.getPattern(), path)) {
                continue;
            }
            hit = true;

            if (!CollectionUtils.isEmpty(command.getMergeGroups())) {
                try {
                    buildWeightsMap(weights, command);
                } catch (JawsFrameworkException e) {
                    log.warn("build weights map fail! {}", e.getMessage());
                    continue;
                }
                List<URL> groupResult = mergeResult(serviceUrl, weights);
                if (mergedResult == null) {
                    mergedResult = new ArrayList<>(groupResult);
                } else {
                    mergedResult.addAll(groupResult);
                }
            } else {
                List<URL> groupResult = discoverOneGroup(serviceUrl);
                if (mergedResult == null) {
                    mergedResult = new ArrayList<>(groupResult);
                } else {
                    mergedResult.addAll(groupResult);
                }
            }
        }

        if (!hit || mergedResult == null) {
            return discoverOneGroup(serviceUrl);
        }

        log.info("mergedResult: size-{} --- {}", mergedResult.size(), mergedResult);
        return mergedResult;
    }

    private void buildWeightsMap(Map<String, Integer> weights, RpcCommand.ClientCommand command) {
        for (String rule : command.getMergeGroups()) {
            String[] gw = rule.split(":");
            int weight = 1;
            if (gw.length > 1) {
                try {
                    weight = Integer.parseInt(gw[1]);
                } catch (NumberFormatException e) {
                    weightConfigError();
                }
                if (weight < 0 || weight > 100) {
                    weightConfigError();
                }
            }
            weights.put(gw[0], weight);
        }
    }

    private List<URL> mergeResult(URL url, Map<String, Integer> weights) {
        List<URL> finalResult = new ArrayList<>();

        if (weights.size() > 1) {
            // encode weight info as a special "rule" protocol URL for LoadBalance
            URL ruleUrl = new URL("rule", url.getHost(), url.getPort(), url.getPath());
            StringBuilder weightsBuilder = new StringBuilder(64);
            for (Map.Entry<String, Integer> entry : weights.entrySet()) {
                weightsBuilder.append(entry.getKey()).append(':').append(entry.getValue()).append(',');
            }
            ruleUrl.addParameter(URLParamType.weights.getName(), weightsBuilder.deleteCharAt(weightsBuilder.length() - 1).toString());
            finalResult.add(ruleUrl);
        }

        for (String key : weights.keySet()) {
            if (groupServiceCache.containsKey(key)) {
                finalResult.addAll(groupServiceCache.get(key));
            } else {
                URL urlTemp = url.createCopy();
                urlTemp.addParameter(URLParamType.group.getName(), key);
                finalResult.addAll(discoverOneGroup(urlTemp));
                registry.subscribeService(urlTemp, this);
            }
        }
        return finalResult;
    }

    private List<URL> discoverOneGroup(URL urlCopy) {
        log.info("CommandServiceManager discover one group. url:{}", urlCopy.toSimpleString());
        String group = urlCopy.getParameter(URLParamType.group.getName(), URLParamType.group.value());
        List<URL> list = groupServiceCache.get(group);
        if (list == null) {
            list = registry.discoverService(urlCopy);
            groupServiceCache.put(group, list);
        }
        return list;
    }

    /**
     * Set the command cache. Called by {@link CommandFailbackRegistry} during initial discover.
     */
    public void setCommandCache(String command) {
        commandStringCache = command != null ? command : "";
        commandCache = RpcCommandUtils.stringToCommand(commandStringCache);
        log.info("CommandServiceManager set commandcache. commandstring:{}, commandcache {}",
                commandStringCache, commandCache == null ? "is null." : "is not null.");
    }

    /**
     * Get the current parsed command. Used by {@link CommandRouter} to apply route rules at call time.
     */
    public RpcCommand getCommandCache() {
        return commandCache;
    }

    public void addNotifyListener(NotifyListener notifyListener) {
        notifySet.add(notifyListener);
    }

    public void removeNotifyListener(NotifyListener notifyListener) {
        notifySet.remove(notifyListener);
    }

    public void setRegistry(CommandFailbackRegistry registry) {
        this.registry = registry;
    }

    private void weightConfigError() {
        throw new JawsFrameworkException("Weight ratio must be an integer in [0,100]");
    }
}
