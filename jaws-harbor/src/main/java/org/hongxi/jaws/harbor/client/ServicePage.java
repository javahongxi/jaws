package org.hongxi.jaws.harbor.client;

import java.util.List;

/**
 * One page of service names plus the size of the whole match set.
 * <p>
 * The two counts are kept apart because that is how Nacos answers a service
 * list: {@code count} is every match, {@code names} only this page. Collapsing
 * them would make paging indistinguishable from a truncated answer.
 *
 * @param names    this page, in the server's order
 * @param total    number of services matching, across all pages
 * @param pageNo   the 1-based page this was taken from
 * @param pageSize the page size that was asked for
 *
 * @author shenhongxi
 */
public record ServicePage(List<String> names, int total, int pageNo, int pageSize) {

    public ServicePage {
        names = names == null ? List.of() : List.copyOf(names);
    }

    /**
     * Whether a further page exists — decided by where this page ends, not by how
     * full it is: a trimmed tail page is still the last one.
     */
    public boolean hasNextPage() {
        return (long) pageNo * pageSize < total;
    }
}
