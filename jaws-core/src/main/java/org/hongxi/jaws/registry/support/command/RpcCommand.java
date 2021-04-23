package org.hongxi.jaws.registry.support.command;

import java.util.Collections;
import java.util.List;

/**
 * Created by shenhongxi on 2021/4/23.
 */
public class RpcCommand {

    private List<ClientCommand> clientCommandList;

    public void sort() {
        Collections.sort(clientCommandList, (o1, o2) -> {
            Integer i1 = o1.getIndex();
            Integer i2 = o2.getIndex();
            if (i1 == null) {
                return -1;
            }
            if (i2 == null) {
                return 1;
            }
            int r = i1.compareTo(i2);
            return r;
        });
    }

    public static class ClientCommand {
        private Integer index;
        private String version;
        private String dc;
        private String pattern;
        private List<String> mergeGroups;
        // 路由规则，当有多个匹配时，按顺序依次过滤结果
        private List<String> routeRules;
        private String remark;

        public Integer getIndex() {
            return index;
        }

        public void setIndex(Integer index) {
            this.index = index;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public String getDc() {
            return dc;
        }

        public void setDc(String dc) {
            this.dc = dc;
        }

        public String getPattern() {
            return pattern;
        }

        public void setPattern(String pattern) {
            this.pattern = pattern;
        }

        public List<String> getMergeGroups() {
            return mergeGroups;
        }

        public void setMergeGroups(List<String> mergeGroups) {
            this.mergeGroups = mergeGroups;
        }

        public List<String> getRouteRules() {
            return routeRules;
        }

        public void setRouteRules(List<String> routeRules) {
            this.routeRules = routeRules;
        }

        public String getRemark() {
            return remark;
        }

        public void setRemark(String remark) {
            this.remark = remark;
        }
    }

    public List<ClientCommand> getClientCommandList() {
        return clientCommandList;
    }

    public void setClientCommandList(List<ClientCommand> clientCommandList) {
        this.clientCommandList = clientCommandList;
    }

    public static class ServerCommand {

    }
}