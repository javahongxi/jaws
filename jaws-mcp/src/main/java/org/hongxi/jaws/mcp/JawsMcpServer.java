package org.hongxi.jaws.mcp;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.hongxi.jaws.mcp.bridge.JsonSchemaGenerator;
import org.hongxi.jaws.mcp.bridge.ServiceMethodSpec;
import org.hongxi.jaws.common.util.ReflectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds and manages an MCP Server that exposes Jaws RPC service methods as MCP Tools.
 * <p>
 * Usage:
 * <pre>{@code
 * JawsMcpServer mcpServer = JawsMcpServer.builder()
 *     .serverInfo("my-jaws-mcp", "1.0.0")
 *     .mcpEndpoint("/mcp")
 *     .addService(DemoService.class, provider)
 *     .build();
 * mcpServer.start();
 * // ...
 * mcpServer.stop();
 * }</pre>
 *
 * @author shenhongxi
 */
public class JawsMcpServer {

    private static final Logger log = LoggerFactory.getLogger(JawsMcpServer.class);

    private final McpSyncServer syncServer;
    private final HttpServletStreamableServerTransportProvider transportProvider;
    private final List<ServiceMethodSpec> methodSpecs;

    private JawsMcpServer(McpSyncServer syncServer,
                          HttpServletStreamableServerTransportProvider transportProvider,
                          List<ServiceMethodSpec> methodSpecs) {
        this.syncServer = syncServer;
        this.transportProvider = transportProvider;
        this.methodSpecs = methodSpecs;
    }

    public McpSyncServer getSyncServer() {
        return syncServer;
    }

    public HttpServletStreamableServerTransportProvider getTransportProvider() {
        return transportProvider;
    }

    public List<ServiceMethodSpec> getMethodSpecs() {
        return Collections.unmodifiableList(methodSpecs);
    }

    public void stop() {
        if (syncServer != null) {
            syncServer.close();
        }
    }

    /**
     * Create service method specifications from a service interface and its Provider.
     * Each public method becomes one specification that can be used by MCP, REST, or other bridges.
     */
    public static List<ServiceMethodSpec> createMethodSpecs(Class<?> interfaceClass,
                                                             org.hongxi.jaws.rpc.Provider<?> provider) {
        List<ServiceMethodSpec> specs = new ArrayList<>();
        String simpleInterfaceName = interfaceClass.getSimpleName();

        // Track method names to handle overloading
        Map<String, Integer> methodNameCount = new LinkedHashMap<>();

        Method[] methods = interfaceClass.getMethods();
        for (Method method : methods) {
            if (!Modifier.isPublic(method.getModifiers()) || method.getDeclaringClass() == Object.class) {
                continue;
            }
            methodNameCount.merge(method.getName(), 1, Integer::sum);
        }

        // Reset counts for action name generation
        Map<String, Integer> methodNameIndex = new HashMap<>();

        for (Method method : methods) {
            if (!Modifier.isPublic(method.getModifiers()) || method.getDeclaringClass() == Object.class) {
                continue;
            }

            String methodName = method.getName();
            int index = methodNameIndex.merge(methodName, 0, Integer::sum) + 1;
            methodNameIndex.put(methodName, index);

            // Build action name
            String actionName;
            int totalCount = methodNameCount.getOrDefault(methodName, 1);
            if (totalCount > 1) {
                // Overloaded method: append index
                actionName = simpleInterfaceName + "_" + methodName + "_" + index;
            } else {
                actionName = simpleInterfaceName + "_" + methodName;
            }

            // Build parameter types
            Class<?>[] paramTypes = method.getParameterTypes();
            String[] paramTypeNames = new String[paramTypes.length];
            for (int i = 0; i < paramTypes.length; i++) {
                paramTypeNames[i] = ReflectUtils.getName(paramTypes[i]);
            }

            // Build description
            String description = buildMethodDescription(interfaceClass, method, paramTypeNames);

            specs.add(new ServiceMethodSpec(
                    actionName,
                    interfaceClass.getName(),
                    methodName,
                    paramTypeNames,
                    method,
                    provider,
                    method.getParameters()
            ));

            log.info("[JawsMcp] Registered method: {} - {}", actionName, description);
        }

        return specs;
    }

    /**
     * @deprecated Use {@link #createMethodSpecs(Class, org.hongxi.jaws.rpc.Provider)} instead.
     *             This method is kept for backward compatibility and returns ServiceMethodSpec list.
     */
    @Deprecated
    public static List<ServiceMethodSpec> createToolSpecs(Class<?> interfaceClass,
                                                           org.hongxi.jaws.rpc.Provider<?> provider) {
        return createMethodSpecs(interfaceClass, provider);
    }

    private static String buildMethodDescription(Class<?> interfaceClass, Method method, String[] paramTypeNames) {
        StringBuilder sb = new StringBuilder();
        sb.append(interfaceClass.getSimpleName()).append(".").append(method.getName()).append("(");
        for (int i = 0; i < paramTypeNames.length; i++) {
            if (i > 0) sb.append(", ");
            // Use simple class name for readability
            String typeName = paramTypeNames[i];
            int lastDot = typeName.lastIndexOf('.');
            if (lastDot >= 0) {
                typeName = typeName.substring(lastDot + 1);
            }
            sb.append(typeName);
        }
        sb.append(")");

        Class<?> returnType = method.getReturnType();
        if (returnType != void.class) {
            String returnTypeName = returnType.getSimpleName();
            sb.append(": ").append(returnTypeName);
        }
        return sb.toString();
    }

    /**
     * Build MCP Tool specifications from ServiceMethodSpec list.
     */
    public static List<McpServerFeatures.SyncToolSpecification> buildSyncToolSpecifications(
            List<ServiceMethodSpec> methodSpecs) {
        List<McpServerFeatures.SyncToolSpecification> specifications = new ArrayList<>();

        for (ServiceMethodSpec spec : methodSpecs) {
            // Skip streaming methods — MCP tools are unary request-response only
            if (spec.isStreamingMethod()) {
                log.info("[JawsMcp] Skipping streaming method for MCP tool: {}", spec.getActionName());
                continue;
            }
            // Generate JSON Schema for the method's input
            Map<String, Object> inputSchema = JsonSchemaGenerator.generateMethodSchema(spec.getParameters());

            // Build MCP Tool
            McpSchema.Tool tool = McpSchema.Tool.builder(spec.getActionName(), inputSchema)
                    .description(buildMethodDescription(
                            getInterfaceClass(spec.getInterfaceName()),
                            spec.getMethod(),
                            spec.getParameterTypes()))
                    .build();

            // Create handler that delegates to JawsMcpToolHandler
            McpServerFeatures.SyncToolSpecification toolSpec = new McpServerFeatures.SyncToolSpecification(
                    tool,
                    (exchange, request) -> JawsMcpToolHandler.handleToolCall(spec, request)
            );

            specifications.add(toolSpec);
        }

        return specifications;
    }

    private static Class<?> getInterfaceClass(String interfaceName) {
        try {
            return Class.forName(interfaceName);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Interface class not found: " + interfaceName, e);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String serverName = "jaws-mcp-server";
        private String serverVersion = "1.0.0";
        private String mcpEndpoint = "/mcp";
        private final List<ServiceMethodSpec> methodSpecs = new ArrayList<>();

        public Builder serverInfo(String name, String version) {
            this.serverName = name;
            this.serverVersion = version;
            return this;
        }

        public Builder mcpEndpoint(String endpoint) {
            this.mcpEndpoint = endpoint;
            return this;
        }

        public Builder addMethodSpecs(List<ServiceMethodSpec> specs) {
            this.methodSpecs.addAll(specs);
            return this;
        }

        /**
         * @deprecated Use {@link #addMethodSpecs(List)} instead.
         */
        @Deprecated
        public Builder addToolSpecs(List<ServiceMethodSpec> specs) {
            return addMethodSpecs(specs);
        }

        public Builder addService(Class<?> interfaceClass, org.hongxi.jaws.rpc.Provider<?> provider) {
            this.methodSpecs.addAll(createMethodSpecs(interfaceClass, provider));
            return this;
        }

        public JawsMcpServer build() {
            // Create transport provider
            HttpServletStreamableServerTransportProvider transportProvider =
                    HttpServletStreamableServerTransportProvider.builder()
                            .mcpEndpoint(mcpEndpoint)
                            .build();

            // Build MCP tool specifications
            List<McpServerFeatures.SyncToolSpecification> toolSpecifications =
                    buildSyncToolSpecifications(methodSpecs);

            // Build MCP Sync Server
            McpSyncServer syncServer = McpServer.sync(transportProvider)
                    .serverInfo(serverName, serverVersion)
                    .tools(toolSpecifications)
                    .build();

            log.info("[JawsMcp] MCP Server built: name={}, tools={}, endpoint={}",
                    serverName, methodSpecs.size(), mcpEndpoint);

            return new JawsMcpServer(syncServer, transportProvider, methodSpecs);
        }
    }
}
