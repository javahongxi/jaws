package org.hongxi.jaws.registry.support.command;

import org.hongxi.jaws.rpc.URL;

/**
 * Created by shenhongxi on 2021/4/23.
 */
public interface CommandListener {

    void notifyCommand(URL refUrl, String commandString);
}