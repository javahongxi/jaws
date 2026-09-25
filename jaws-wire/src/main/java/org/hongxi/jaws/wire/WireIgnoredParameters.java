package org.hongxi.jaws.wire;

import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;

/**
 * Reports configuration a wire endpoint accepts but does not act on.
 * <p>
 * {@code heartbeat}, {@code sendReconnect} and {@code maxContentLength} are read
 * by the binary and adaptive transports only. A URL is shared config across
 * transports, so wire cannot reject these keys, but accepting them in silence
 * leaves the operator believing a keepalive or a ceiling is in place. Warning
 * once per endpoint is the minimum honest answer.
 *
 * @author shenhongxi
 */
final class WireIgnoredParameters {

    /** Parameters that never reach a wire code path. */
    private static final String[] IGNORED = {
            UrlParam.Transport.HEARTBEAT.getName(),
            UrlParam.Client.SEND_RECONNECT.getName(),
            UrlParam.Transport.MAX_CONTENT_LENGTH.getName(),
    };

    private WireIgnoredParameters() {
    }

    /**
     * Logs one warning per ignored parameter present on {@code url}.
     *
     * @param log       the component's own logger, so the warning is attributed
     *                  to the transport that ignored the value
     * @param url       the endpoint URL
     * @param component name to quote in the message (e.g. {@code WireClient})
     */
    static void warnIgnored(Logger log, URL url, String component) {
        for (String name : IGNORED) {
            String value = url.getParameter(name);
            if (value != null) {
                log.warn("{} ignores parameter {}={} on {}: only the binary and adaptive "
                        + "transports act on it", component, name, value, url.getUri());
            }
        }
    }
}
