package org.hongxi.jaws.harbor.model.request;

import org.hongxi.jaws.harbor.model.Request;

/**
 * List all registered service names in a namespace/group.
 *
 * @author shenhongxi
 */
public class ServiceListRequest extends Request {

    private String groupName;
    /** 1-based, as in Nacos {@code ServiceUtil.pageServiceName}. */
    private int pageNo;
    private int pageSize;

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public int getPageNo() {
        return pageNo;
    }

    public void setPageNo(int pageNo) {
        this.pageNo = pageNo;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }
}
