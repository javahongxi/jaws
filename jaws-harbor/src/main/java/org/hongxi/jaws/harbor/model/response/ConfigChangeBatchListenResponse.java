package org.hongxi.jaws.harbor.model.response;

import org.hongxi.jaws.harbor.model.Response;

import java.util.ArrayList;
import java.util.List;

/**
 * Reply to a {@code ConfigBatchListenRequest} — the nacos
 * {@code ConfigChangeBatchListenResponse} shape (this, not a mirror-named
 * "ConfigBatchListenResponse", is the type nacos-client casts the reply to).
 * Only the keys whose stored md5 differs from the watcher's are listed; the
 * client queries each of them to pull the new content.
 *
 * @author shenhongxi
 */
public class ConfigChangeBatchListenResponse extends Response {

    private List<ConfigContext> changedConfigs = new ArrayList<>();

    public ConfigChangeBatchListenResponse() {
        setResultCode(200);
        setSuccess(true);
    }

    public void addChangeConfig(String dataId, String group, String tenant) {
        ConfigContext context = new ConfigContext();
        context.setDataId(dataId);
        context.setGroup(group);
        context.setTenant(tenant);
        changedConfigs.add(context);
    }

    public List<ConfigContext> getChangedConfigs() {
        return changedConfigs;
    }

    public void setChangedConfigs(List<ConfigContext> changedConfigs) {
        this.changedConfigs = changedConfigs;
    }

    /**
     * One changed key, addressed exactly as the watcher named it.
     */
    public static class ConfigContext {

        private String group;

        private String dataId;

        private String tenant;

        public String getGroup() {
            return group;
        }

        public void setGroup(String group) {
            this.group = group;
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