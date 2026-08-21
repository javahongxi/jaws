package org.hongxi.jaws.cluster.router;

import org.hongxi.jaws.cluster.Router;
import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link Router} that filters providers by tag for gray release / canary deployment.
 * <p>
 * Routing rules:
 * <ol>
 *   <li>If the request carries a tag attachment ({@code "tag"}), only providers whose URL
 *       parameter {@code tag} matches the requested tag are returned</li>
 *   <li>If no providers match the requested tag, the full candidate list is returned as fallback
 *       (to avoid total call failure when gray instances are temporarily unavailable)</li>
 *   <li>If the request does NOT carry a tag, all providers are returned (tagged and untagged
 *       are both eligible)</li>
 * </ol>
 * <p>
 * Consumer usage:
 * <pre>
 *   RpcContext.getContext().setRpcAttachment("tag", "gray");
 *   demoService.hello("jaws");  // routed to providers with tag=gray
 * </pre>
 * <p>
 * Provider usage (Spring Boot):
 * <pre>
 *   &#64;JawsService(tag = "gray")
 *   public class DemoServiceImpl implements DemoService { ... }
 * </pre>
 *
 * @param <T> service type
 */
public class TagRouter<T> implements Router<T> {

    private static final Logger log = LoggerFactory.getLogger(TagRouter.class);

    @Override
    public List<Reference<T>> route(List<Reference<T>> references, Request request) {
        String requestTag = request.getAttachments() != null
                ? request.getAttachments().get(JawsConstants.TAG_ATTACHMENT)
                : null;

        if (requestTag == null || requestTag.isEmpty()) {
            return references;
        }

        List<Reference<T>> tagged = new ArrayList<>();
        for (Reference<T> ref : references) {
            URL serviceUrl = ref.getServiceUrl();
            if (serviceUrl == null) {
                continue;
            }
            String providerTag = serviceUrl.getParameter(URLParamType.tag.getName());
            if (requestTag.equals(providerTag)) {
                tagged.add(ref);
            }
        }

        if (tagged.isEmpty()) {
            log.debug("TagRouter: no providers match tag='{}', falling back to all {} candidates",
                    requestTag, references.size());
            return references;
        }

        log.debug("TagRouter: filtered to {} providers with tag='{}'", tagged.size(), requestTag);
        return tagged;
    }
}
