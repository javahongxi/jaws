package org.hongxi.jaws.registry.support.command;

import org.hongxi.jaws.cluster.Router;
import org.hongxi.jaws.common.util.CollectionUtils;
import org.hongxi.jaws.common.util.NetUtils;
import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * CommandRouter applies IP-based route rules at each RPC call,
 * instead of at service discover time.
 * <p>
 * It reads the current {@link RpcCommand} from {@link CommandServiceManager}
 * and filters out providers whose IP does not match the configured route rules.
 *
 * @param <T> service type
 */
public class CommandRouter<T> implements Router<T> {

    private static final Logger log = LoggerFactory.getLogger(CommandRouter.class);
    private static final Pattern IP_PATTERN = Pattern.compile("^!?[0-9.]*\\*?$");

    private final CommandServiceManager commandManager;
    private final String localIP;
    private final String servicePath;

    public CommandRouter(CommandServiceManager commandManager, String servicePath) {
        this.commandManager = commandManager;
        this.servicePath = servicePath;
        this.localIP = NetUtils.getLocalAddress().getHostAddress();
    }

    @Override
    public List<Reference<T>> route(List<Reference<T>> references, Request request) {
        RpcCommand command = commandManager.getCommandCache();
        if (command == null || CollectionUtils.isEmpty(command.getClientCommands())) {
            return references;
        }

        List<String> routeRules = findMatchingRouteRules(command);
        if (CollectionUtils.isEmpty(routeRules)) {
            return references;
        }

        return applyRouteRules(references, routeRules);
    }

    /**
     * Find route rules from the first matching client command.
     * Unlike the old discover-time logic, we now evaluate ALL matching commands
     * and accumulate their route rules.
     */
    private List<String> findMatchingRouteRules(RpcCommand command) {
        List<String> allRules = new ArrayList<>();
        for (RpcCommand.ClientCommand cmd : command.getClientCommands()) {
            if (RpcCommandUtils.match(cmd.getPattern(), servicePath)) {
                if (!CollectionUtils.isEmpty(cmd.getRouteRules())) {
                    allRules.addAll(cmd.getRouteRules());
                }
            }
        }
        return allRules;
    }

    /**
     * Filter references based on IP route rules.
     * Each rule has the format: "fromIP to toIP", supporting wildcards (*) and negation (!).
     */
    private List<Reference<T>> applyRouteRules(List<Reference<T>> references, List<String> routeRules) {
        List<Reference<T>> result = new ArrayList<>(references);

        for (String routeRule : routeRules) {
            String[] fromTo = routeRule.replaceAll("\\s+", "").split("to");
            if (fromTo.length != 2) {
                log.warn("Invalid route rule format: {}", routeRule);
                continue;
            }

            String from = fromTo[0];
            String to = fromTo[1];
            if (from.isEmpty() || to.isEmpty() || !IP_PATTERN.matcher(from).find() || !IP_PATTERN.matcher(to).find()) {
                log.warn("Invalid route rule pattern: {}", routeRule);
                continue;
            }

            boolean negateFrom = from.startsWith("!");
            boolean negateTo = to.startsWith("!");
            if (negateFrom) {
                from = from.substring(1);
            }
            if (negateTo) {
                to = to.substring(1);
            }

            boolean matchFrom = matchIp(from, localIP, negateFrom);
            if (!matchFrom) {
                continue;
            }

            // from matches, apply to-filter on providers
            Iterator<Reference<T>> it = result.iterator();
            while (it.hasNext()) {
                Reference<T> ref = it.next();
                String providerHost = ref.getServiceUrl().getHost();
                boolean matchTo = matchIp(to, providerHost, negateTo);
                if (!matchTo) {
                    it.remove();
                    log.debug("Route rule [{}] filtered out provider: {}", routeRule, providerHost);
                }
            }
        }

        return result;
    }

    /**
     * Match an IP address against a pattern with optional wildcard.
     *
     * @param pattern the IP pattern (e.g. "192.168.1.*" or "10.0.0.1")
     * @param ip      the IP address to test
     * @param negate  whether to negate the result
     * @return true if the IP matches the pattern (after negation)
     */
    private boolean matchIp(String pattern, String ip, boolean negate) {
        boolean match;
        int idx = pattern.indexOf('*');
        if (idx != -1) {
            match = ip.startsWith(pattern.substring(0, idx));
        } else {
            match = ip.equals(pattern);
        }
        return negate ? !match : match;
    }
}
