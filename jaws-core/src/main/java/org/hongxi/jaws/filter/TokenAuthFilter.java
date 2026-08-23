package org.hongxi.jaws.filter;

import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.common.extension.Extension;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.rpc.Caller;
import org.hongxi.jaws.rpc.Provider;
import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Token-based service authentication filter.
 * <p>
 * Consumer side: reads token from provider's service URL and attaches it to the request.
 * Provider side: validates the token in the request attachment against its own URL's token.
 * <p>
 * If the provider has no token configured, authentication is skipped (backward compatible).
 */
@Extension(value = "tokenAuth", order = 1, keys = {"service", "reference"})
public class TokenAuthFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(TokenAuthFilter.class);

    private static final String TOKEN_ATTACHMENT = "token";

    @Override
    public Response filter(Caller<?> caller, Request request) {
        if (caller instanceof Provider) {
            return filterProvider(caller, request);
        } else {
            return filterConsumer(caller, request);
        }
    }

    private Response filterConsumer(Caller<?> caller, Request request) {
        String token = null;
        if (caller instanceof Reference<?> ref) {
            token = ref.getServiceUrl().getParameter(UrlParam.Identity.TOKEN.getName());
        }
        if (token != null && !token.isEmpty()) {
            request.setAttachment(TOKEN_ATTACHMENT, token);
        }
        return caller.call(request);
    }

    private Response filterProvider(Caller<?> caller, Request request) {
        String expectedToken = caller.getUrl().getParameter(UrlParam.Identity.TOKEN.getName());
        if (expectedToken == null || expectedToken.isEmpty()) {
            // no token configured, skip auth
            return caller.call(request);
        }
        String actualToken = request.getAttachments().get(TOKEN_ATTACHMENT);
        if (!expectedToken.equals(actualToken)) {
            log.warn("Token auth failed: service={}, method={}, remote={}",
                    request.getInterfaceName(), request.getMethodName(),
                    request.getAttachments().get(UrlParam.Server.HOST.getName()));
            throw new JawsServiceException("Token authentication failed for service: " + request.getInterfaceName());
        }
        return caller.call(request);
    }
}
