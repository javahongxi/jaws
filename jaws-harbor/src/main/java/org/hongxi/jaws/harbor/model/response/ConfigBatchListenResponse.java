package org.hongxi.jaws.harbor.model.response;

import org.hongxi.jaws.harbor.model.Response;

import java.util.List;

/**
 * Response for ConfigBatchListenRequest.
 * <p>
 * Harbor does not support config center functionality, so this response
 * always returns an empty listen context list. This prevents nacos-client
 * from repeatedly sending config listen requests.
 *
 * @author shenhongxi
 */
public class ConfigBatchListenResponse extends Response {

    private List<ConfigListenContext> configListenContexts;

    public ConfigBatchListenResponse() {
    }

    public static ConfigBatchListenResponse ok() {
        ConfigBatchListenResponse response = new ConfigBatchListenResponse();
        response.setResultCode(200);
        response.setSuccess(true);
        response.setConfigListenContexts(List.of());
        return response;
    }

    public List<ConfigListenContext> getConfigListenContexts() {
        return configListenContexts;
    }

    public void setConfigListenContexts(List<ConfigListenContext> configListenContexts) {
        this.configListenContexts = configListenContexts;
    }

    /**
     * Empty placeholder — nacos-client expects this inner structure
     * but Harbor never returns any actual config listen contexts.
     */
    public static class ConfigListenContext {
        private String group;
        private String dataId;
        private long md5;

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

        public long getMd5() {
            return md5;
        }

        public void setMd5(long md5) {
            this.md5 = md5;
        }
    }
}
