package org.hongxi.jaws.wire;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The decompressions this side can perform, and which of them to advertise to
 * the peer in {@code grpc-accept-encoding}.
 * <p>
 * Two distinct questions, and the {@code advertised} flag keeps them apart:
 * lookup on receipt ignores it — if a peer sends an encoding we know how to
 * process, the wire format says we should decode it regardless of what we
 * advertised — while the header that invites the peer to compress lists only
 * the advertised ones. {@code identity} is therefore registered but not
 * advertised, which is why the default advertisement is exactly
 * {@code "gzip"}: naming identity would add nothing, since a frame without the
 * compressed flag needs no agreement.
 * <p>
 * Immutable, unlike {@link CompressorRegistry}: the advertised header is a
 * function of the whole set, so it is computed once per table and every change
 * returns a new registry.
 * <p>
 * Mirrors {@code io.grpc.DecompressorRegistry}.
 *
 * @author shenhongxi
 */
public final class DecompressorRegistry {

    /**
     * @return a new registry that can decompress nothing, not even identity
     */
    public static DecompressorRegistry emptyInstance() {
        return new DecompressorRegistry();
    }

    private static final DecompressorRegistry DEFAULT_INSTANCE = emptyInstance()
            .with(new Codec.Gzip(), true)
            .with(Codec.Identity.NONE, false);

    /**
     * @return the instance gRPC uses when none is configured; gzip advertised,
     *         identity registered but not advertised
     */
    public static DecompressorRegistry getDefaultInstance() {
        return DEFAULT_INSTANCE;
    }

    private final Map<String, DecompressorInfo> decompressors;
    /** The comma-joined advertisement, computed with the table it came from. */
    private final String advertisedEncodings;

    private DecompressorRegistry() {
        decompressors = new LinkedHashMap<>(0);
        advertisedEncodings = "";
    }

    private DecompressorRegistry(Decompressor d, boolean advertised, DecompressorRegistry parent) {
        String encoding = d.getMessageEncoding();
        if (encoding.contains(",")) {
            throw new IllegalArgumentException(
                    "Comma is currently not allowed in message encoding");
        }
        Map<String, DecompressorInfo> newDecompressors =
                new LinkedHashMap<>(parent.decompressors.size() + 1);
        for (DecompressorInfo di : parent.decompressors.values()) {
            if (!di.decompressor.getMessageEncoding().equals(encoding)) {
                newDecompressors.put(di.decompressor.getMessageEncoding(),
                        new DecompressorInfo(di.decompressor, di.advertised));
            }
        }
        newDecompressors.put(encoding, new DecompressorInfo(d, advertised));

        decompressors = Collections.unmodifiableMap(newDecompressors);
        advertisedEncodings = String.join(",", advertisedNames(newDecompressors));
    }

    /**
     * Registers a decompressor, returning a new registry that leaves this one
     * untouched.
     *
     * @param decompressor the decompressor to add; a previous registration
     *                     under the same encoding is replaced
     * @param advertised   whether the encoding appears in the
     *                     {@code grpc-accept-encoding} header this side sends
     * @return a registry including the new decompressor
     * @throws IllegalArgumentException if the encoding name contains a comma
     */
    public DecompressorRegistry with(Decompressor decompressor, boolean advertised) {
        return new DecompressorRegistry(decompressor, advertised, this);
    }

    /**
     * @return every encoding this side can decompress, advertised or not, in
     *         registration order
     */
    public Set<String> getKnownMessageEncodings() {
        return decompressors.keySet();
    }

    /**
     * @return the encodings to offer the peer. The specification says nothing
     *         about ordering or preference, so this set carries no order beyond
     *         registration
     */
    public Set<String> getAdvertisedMessageEncodings() {
        Set<String> advertised = new HashSet<>(decompressors.size());
        for (Map.Entry<String, DecompressorInfo> entry : decompressors.entrySet()) {
            if (entry.getValue().advertised) {
                advertised.add(entry.getKey());
            }
        }
        return Collections.unmodifiableSet(advertised);
    }

    /**
     * The advertisement as it goes on the wire, joined when the table was built
     * rather than on every call. Package-private because it is a framing
     * detail, not part of what a caller configures.
     *
     * @return the comma-joined advertised encodings, empty when none
     */
    String rawAdvertisedEncodings() {
        return advertisedEncodings;
    }

    /**
     * Finds the decompressor for an encoding received from the peer.
     * <p>
     * This ignores whether the decompressor is advertised. Per the wire format,
     * if we know how to process an encoding we attempt to, whether or not it
     * was part of what we offered.
     *
     * @param messageEncoding the name from {@code grpc-encoding}
     * @return the decompressor, or {@code null} when unsupported — which the
     *         server answers with {@code UNIMPLEMENTED}
     */
    public Decompressor lookupDecompressor(String messageEncoding) {
        DecompressorInfo info = decompressors.get(messageEncoding);
        return info != null ? info.decompressor : null;
    }

    private static Iterable<String> advertisedNames(Map<String, DecompressorInfo> table) {
        return table.entrySet().stream()
                .filter(entry -> entry.getValue().advertised)
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toList());
    }

    /** The registration of one encoding, with its advertising decision. */
    private static final class DecompressorInfo {
        final Decompressor decompressor;
        final boolean advertised;

        DecompressorInfo(Decompressor decompressor, boolean advertised) {
            this.decompressor = decompressor;
            this.advertised = advertised;
        }
    }
}
