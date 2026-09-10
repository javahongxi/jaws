package org.hongxi.jaws.harbor.distro;

import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A no-op implementation of {@link HarborNodeTransport} for single-node
 * Harbor deployments. All operations return immediately without sending
 * any data.
 *
 * @author shenhongxi
 */
public class NoopHarborNodeTransport implements HarborNodeTransport {

    private static final Logger log = LoggerFactory.getLogger(NoopHarborNodeTransport.class);

    @Override
    public boolean syncData(String targetAddress, String resourceType, String resourceKey,
                            String operation, byte[] content) {
        log.debug("[harbor] noop sync to {} for {}:{} — single-node mode", targetAddress,
                resourceType, resourceKey);
        return true;
    }

    @Override
    public boolean syncVerify(String targetAddress, String resourceType, JSONObject checksums) {
        return true;
    }

    @Override
    public byte[] getSnapshot(String targetAddress, String resourceType) {
        return null;
    }

    @Override
    public void shutdown() {
        // nothing to shut down
    }
}
