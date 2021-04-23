package org.hongxi.jaws.closable;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

/**
 * In order to shutdown jaws server running in tomcat(run tomcat's shutdown.sh rather than kill PID manually),add ShutDownHookListener to web.xml
 * 为了关闭在tomcat中运行的 jaws server（运行tomcat的shutdown.sh关闭而不是手动kill pid），在web.xml中添加ShutDownHookListener
 *
 * Created by shenhongxi on 2021/4/23.
 */
public class ShutDownHookListener implements ServletContextListener {
    @Override
    public void contextInitialized(ServletContextEvent sce) {
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        ShutdownHook.runHook(true);
    }
}