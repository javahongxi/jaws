package org.hongxi.jaws.registry.support.command;

import org.apache.commons.lang3.StringUtils;
import org.hongxi.jaws.registry.FailbackRegistry;
import org.hongxi.jaws.registry.NotifyListener;
import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Created by shenhongxi on 2021/4/23.
 */
public abstract class CommandFailbackRegistry extends FailbackRegistry {
    
    private static final Logger log = LoggerFactory.getLogger(CommandFailbackRegistry.class);

    private ConcurrentHashMap<URL, CommandServiceManager> commandManagerMap;

    public CommandFailbackRegistry(URL url) {
        super(url);
        commandManagerMap = new ConcurrentHashMap<>();
        log.info("CommandFailbackRegistry init. url: {}", url.toSimpleString());
    }

    @Override
    protected void doSubscribe(URL url, final NotifyListener listener) {
        log.info("CommandFailbackRegistry subscribe. url: {}", url.toSimpleString());
        URL urlCopy = url.createCopy();
        CommandServiceManager manager = getCommandServiceManager(urlCopy);
        manager.addNotifyListener(listener);

        subscribeService(urlCopy, manager);
        subscribeCommand(urlCopy, manager);

        List<URL> urls = doDiscover(urlCopy);
        if (urls != null && urls.size() > 0) {
            this.notify(urlCopy, listener, urls);
        }
    }

    @Override
    protected void doUnsubscribe(URL url, NotifyListener listener) {
        log.info("CommandFailbackRegistry unsubscribe. url: " + url.toSimpleString());
        URL urlCopy = url.createCopy();
        CommandServiceManager manager = commandManagerMap.get(urlCopy);

        manager.removeNotifyListener(listener);
        unsubscribeService(urlCopy, manager);
        unsubscribeCommand(urlCopy, manager);

    }

    @Override
    protected List<URL> doDiscover(URL url) {
        log.info("CommandFailbackRegistry discover. url: " + url.toSimpleString());
        List<URL> finalResult;

        URL urlCopy = url.createCopy();
        String commandStr = discoverCommand(urlCopy);
        RpcCommand rpcCommand = null;
        if (StringUtils.isNotEmpty(commandStr)) {
            rpcCommand = RpcCommandUtils.stringToCommand(commandStr);
        }

        log.info("CommandFailbackRegistry discover command. commandStr: " + commandStr + ", rpccommand "
                + (rpcCommand == null ? "is null." : "is not null."));

        if (rpcCommand != null) {
            rpcCommand.sort();
            CommandServiceManager manager = getCommandServiceManager(urlCopy);
            finalResult = manager.discoverServiceWithCommand(urlCopy, new HashMap<>(), rpcCommand);

            // 在subscribeCommon时，可能订阅完马上就notify，导致首次notify指令时，可能还有其他service没有完成订阅，
            // 此处先对manager更新指令，避免首次订阅无效的问题。
            manager.setCommandCache(commandStr);
        } else {
            finalResult = discoverService(urlCopy);
        }

        log.info("CommandFailbackRegistry discover size: {}", finalResult == null ? "0" : finalResult.size());

        return finalResult;
    }

    public List<URL> commandPreview(URL url, RpcCommand rpcCommand, String previewIP) {
        List<URL> finalResult;
        URL urlCopy = url.createCopy();

        if (rpcCommand != null) {
            CommandServiceManager manager = getCommandServiceManager(urlCopy);
            finalResult = manager.discoverServiceWithCommand(urlCopy, new HashMap<>(), rpcCommand, previewIP);
        } else {
            finalResult = discoverService(urlCopy);
        }

        return finalResult;
    }

    private CommandServiceManager getCommandServiceManager(URL urlCopy) {
        CommandServiceManager manager = commandManagerMap.get(urlCopy);
        if (manager == null) {
            manager = new CommandServiceManager(urlCopy);
            manager.setRegistry(this);
            CommandServiceManager manager1 = commandManagerMap.putIfAbsent(urlCopy, manager);
            if (manager1 != null) manager = manager1;
        }
        return manager;
    }

    // for UnitTest
    public ConcurrentHashMap<URL, CommandServiceManager> getCommandManagerMap() {
        return commandManagerMap;
    }

    protected abstract void subscribeService(URL url, ServiceListener listener);

    protected abstract void subscribeCommand(URL url, CommandListener listener);

    protected abstract void unsubscribeService(URL url, ServiceListener listener);

    protected abstract void unsubscribeCommand(URL url, CommandListener listener);

    protected abstract List<URL> discoverService(URL url);

    protected abstract String discoverCommand(URL url);

}