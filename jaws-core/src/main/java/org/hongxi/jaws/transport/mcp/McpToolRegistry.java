package org.hongxi.jaws.transport.mcp;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of MCP tools exposed through the Streamable HTTP transport.
 * <p>
 * Each tool has a name, description, JSON Schema input specification, and
 * an execution function. Tools are the MCP mechanism for AI agents to invoke
 * server-side capabilities — analogous to Jaws RPC service methods.
 * <p>
 * Thread-safe: tools can be registered and unregistered concurrently.
 *
 * @author shenhongxi
 */
public class McpToolRegistry {

    /**
     * An MCP tool that can be listed and called by MCP clients.
     *
     * @param name        unique tool name (e.g. "hello")
     * @param description human-readable description for the AI model
     * @param inputSchema JSON Schema object describing the tool's parameters
     * @param executor    function that executes the tool with the given arguments
     */
    public record Tool(String name, String description, Map<String, Object> inputSchema, Executor executor) {
    }

    /**
     * Functional interface for tool execution.
     */
    @FunctionalInterface
    public interface Executor {
        /**
         * Execute the tool.
         *
         * @param arguments the tool arguments as a JSON-like map
         * @return the tool result (will be serialized to JSON)
         */
        Object execute(Map<String, Object> arguments) throws Exception;
    }

    private final ConcurrentHashMap<String, Tool> tools = new ConcurrentHashMap<>();

    /**
     * Register a tool.
     *
     * @param tool the tool to register
     */
    public void register(Tool tool) {
        tools.put(tool.name(), tool);
    }

    /**
     * Unregister a tool by name.
     *
     * @param name the tool name to remove
     * @return true if the tool was present and removed
     */
    public boolean unregister(String name) {
        return tools.remove(name) != null;
    }

    /**
     * Look up a tool by name.
     *
     * @param name the tool name
     * @return the tool, or null if not found
     */
    public Tool get(String name) {
        return tools.get(name);
    }

    /**
     * @return all registered tools as an unmodifiable snapshot
     */
    public Map<String, Tool> getAll() {
        return Map.copyOf(tools);
    }

    /**
     * @return the number of registered tools
     */
    public int size() {
        return tools.size();
    }
}
