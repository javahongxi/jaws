package org.hongxi.jaws.registry.support.command;

import com.alibaba.fastjson2.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility methods for command parsing and pattern matching.
 */
public class RpcCommandUtils {

    private static final Logger log = LoggerFactory.getLogger(RpcCommandUtils.class);

    /**
     * Parse a command JSON string into an {@link RpcCommand} object.
     *
     * @param commandString JSON string
     * @return parsed command, or null if parsing fails
     */
    public static RpcCommand stringToCommand(String commandString) {
        try {
            return JSON.parseObject(commandString, RpcCommand.class);
        } catch (Exception e) {
            log.error("Command config error: invalid JSON format!");
            return null;
        }
    }

    /**
     * Serialize a command to JSON string.
     *
     * @param command the command object
     * @return JSON string
     */
    public static String commandToString(RpcCommand command) {
        return JSON.toJSONString(command);
    }

    /**
     * Match a service interface path against a pattern expression.
     * <p>
     * The pattern supports:
     * <ul>
     *   <li>Exact match: {@code "com.example.DemoService"}</li>
     *   <li>Wildcard suffix: {@code "com.example.*"} matches any interface starting with "com.example."</li>
     * </ul>
     *
     * @param expression the pattern expression
     * @param path       the service interface name to match
     * @return true if the path matches the expression
     */
    public static boolean match(String expression, String path) {
        if (expression == null || expression.isEmpty()) {
            return false;
        }
        int idx = expression.indexOf('*');
        if (idx != -1) {
            return path.startsWith(expression.substring(0, idx));
        }
        return expression.equals(path);
    }
}
