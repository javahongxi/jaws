package org.hongxi.jaws.metadata;

/**
 * Internal service exposed by every provider running in application-level
 * registration mode ({@code registerMode=instance}), on the same port as the
 * business services and never registered to any registry.
 * <p>
 * Consumers call it point-to-point to fetch the {@link MetadataInfo} of a given
 * revision (local metadata mode, the default in Dubbo 3): the registry only carries
 * lightweight instances with a revision pointer, while the heavy service metadata
 * lives in the provider's memory and is pulled over this RPC.
 *
 * @see ApplicationMetadataManager
 */
public interface MetadataService {

    /**
     * Look up metadata by revision, purely in memory.
     *
     * @param revision the content hash carried by the discovered instance
     * @return the matching metadata (current or historical), or null if this
     *         instance has never published that revision
     */
    MetadataInfo getMetadataInfo(String revision);
}