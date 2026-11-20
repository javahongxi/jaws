package org.hongxi.jaws.harbor.model.request;

import java.util.ArrayList;
import java.util.List;

/**
 * Register (or drop) a batch of config listens — the nacos
 * {@code ConfigBatchListenRequest} shape. Each context carries the md5 the
 * client currently holds; the server answers with the keys whose stored md5
 * differs, and the client queries those. {@code listen=false} removes the
 * connection's watch on the listed keys.
 *
 * @author shenhongxi
 */
public class ConfigBatchListenRequest extends AbstractConfigRequest {

    private boolean listen = true;

    private List<ConfigListenContext> configListenContexts = new ArrayList<>();

    public boolean isListen() {
        return listen;
    }

    public void setListen(boolean listen) {
        this.listen = listen;
    }

    public List<ConfigListenContext> getConfigListenContexts() {
        return configListenContexts;
    }

    public void setConfigListenContexts(List<ConfigListenContext> configListenContexts) {
        this.configListenContexts = configListenContexts;
    }

    public void addConfigListenContext(String group, String dataId, String tenant, String md5) {
        ConfigListenContext context = new ConfigListenContext();
        context.setGroup(group);
        context.setDataId(dataId);
        context.setTenant(tenant);
        context.setMd5(md5);
        configListenContexts.add(context);
    }

    /**
     * One watched key plus the md5 the watcher currently holds.
     */
    public static class ConfigListenContext {

        private String group;

        private String md5;

        private String dataId;

        private String tenant;

        public String getGroup() {
            return group;
        }

        public void setGroup(String group) {
            this.group = group;
        }

        public String getMd5() {
            return md5;
        }

        public void setMd5(String md5) {
            this.md5 = md5;
        }

        public String getDataId() {
            return dataId;
        }

        public void setDataId(String dataId) {
            this.dataId = dataId;
        }

        public String getTenant() {
            return tenant;
        }

        public void setTenant(String tenant) {
            this.tenant = tenant;
        }
    }
}