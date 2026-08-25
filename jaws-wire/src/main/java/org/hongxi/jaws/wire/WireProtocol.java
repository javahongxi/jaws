package org.hongxi.jaws.wire;

import org.hongxi.jaws.common.extension.Extension;
import org.hongxi.jaws.protocol.AbstractProtocol;
import org.hongxi.jaws.rpc.Exporter;
import org.hongxi.jaws.rpc.Provider;
import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.URL;

/**
 * Wire (gRPC wire format) protocol implementation, registered as
 * {@code @Extension("wire")}.
 * <p>
 * Creates {@link WireExporter} for service exposure and {@link WireReference}
 * for consumer references. Both use protobuf {@link com.google.protobuf.Message}
 * types extracted from the service interface to bridge between the Jaws
 * framework pipeline (which carries raw protobuf bytes) and the gRPC wire
 * format (which requires 5-byte length-prefixed frames).
 * <p>
 * Using this protocol enables the full Jaws infrastructure — registry, cluster
 * (failover/failfast), load balancing, routing, and filter chain — with gRPC
 * wire format as the transport.
 *
 * @author shenhongxi
 */
@Extension("wire")
public class WireProtocol extends AbstractProtocol {

    @Override
    protected <T> Exporter<T> createExporter(Provider<T> provider) {
        URL url = provider.getUrl();
        return new WireExporter<>(provider, url);
    }

    @Override
    protected <T> Reference<T> createReference(Class<T> interfaceClass, URL url) {
        return new WireReference<>(interfaceClass, url);
    }
}
