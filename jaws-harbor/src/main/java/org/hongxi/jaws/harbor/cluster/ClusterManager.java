package org.hongxi.jaws.harbor.cluster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the set of cluster members (peer Harbor servers).
 * <p>
 * Members can be added/removed dynamically. The manager provides
 * {@link #allMembers()} for iterating over all known peers and
 * {@link #allMembersExceptSelf()} for excluding the local node.
 *
 * @author shenhongxi
 */
public class ClusterManager {

    private static final Logger log = LoggerFactory.getLogger(ClusterManager.class);

    private final Set<ClusterMember> members = ConcurrentHashMap.newKeySet();
    private volatile String selfAddress;

    /**
     * Set the local node's own address so it can be excluded from
     * peer lists.
     */
    public void setSelfAddress(String selfAddress) {
        this.selfAddress = selfAddress;
    }

    public String getSelfAddress() {
        return selfAddress;
    }

    public void addMember(ClusterMember member) {
        if (members.add(member)) {
            log.info("[harbor] cluster member added: {}", member.address());
        }
    }

    public void removeMember(ClusterMember member) {
        if (members.remove(member)) {
            log.info("[harbor] cluster member removed: {}", member.address());
        }
    }

    public void addMembers(Collection<ClusterMember> newMembers) {
        members.addAll(newMembers);
    }

    /**
     * @return all known cluster members (including self if it was added)
     */
    public Set<ClusterMember> allMembers() {
        return Collections.unmodifiableSet(members);
    }

    /**
     * @return all members except the local node
     */
    public Set<ClusterMember> allMembersExceptSelf() {
        if (selfAddress == null) {
            return allMembers();
        }
        return members.stream()
                .filter(m -> !m.address().equals(selfAddress))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public int size() {
        return members.size();
    }

    public boolean isEmpty() {
        return members.isEmpty();
    }
}
