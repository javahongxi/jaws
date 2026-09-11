package org.hongxi.jaws.harbor.model.request;

import org.hongxi.jaws.harbor.model.Request;

import java.util.List;
import java.util.Map;

/**
 * Batch add or remove config listeners.
 *
 * @author shenhongxi
 */
public class ConfigBatchListenRequest extends Request {

    private boolean listen = true;
    private List<ConfigListenContext> configListenContexts;

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

    /**
     * A single config listen context entry.
     */
    public static class ConfigListenContext {

        private String dataId;
        private String group;
        private String tenant;
        private String md5;

        public String getDataId() {
            return dataId;
        }

        public void setDataId(String dataId) {
            this.dataId = dataId;
        }

        public String getGroup() {
            return group;
        }

        public void setGroup(String group) {
            this.group = group;
        }

        public String getTenant() {
            return tenant;
        }

        public void setTenant(String tenant) {
            this.tenant = tenant;
        }

        public String getMd5() {
            return md5;
        }

        public void setMd5(String md5) {
            this.md5 = md5;
        }
    }
}
