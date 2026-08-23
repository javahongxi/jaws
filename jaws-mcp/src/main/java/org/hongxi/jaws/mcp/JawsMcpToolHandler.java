package org.hongxi.jaws.mcp;

import com.alibaba.fastjson2.JSON;
import io.modelcontextprotocol.spec.McpSchema;
import org.hongxi.jaws.mcp.bridge.ArgumentConverter;
import org.hongxi.jaws.mcp.bridge.ServiceMethodSpec;
import org.hongxi.jaws.common.util.ReflectUtils;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.DefaultResponse;
import org.hongxi.jaws.rpc.Provider;
import org.hongxi.jaws.rpc.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Handles MCP Tool calls by bridging them to Jaws {@link Provider} invocations.
 * <p>
 * Converts {@link McpSchema.CallToolRequest} arguments into a Jaws
 * {@link org.hongxi.jaws.rpc.Request}, invokes the provider, and converts
 * the {@link Response} back to a {@link McpSchema.CallToolResult}.
 * <p>
 * Argument conversion is delegated to {@link ArgumentConverter} which is shared
 * across protocol bridges (MCP, REST, etc.).
 *
 * @author shenhongxi
 */
public class JawsMcpToolHandler {

    private static final Logger log = LoggerFactory.getLogger(JawsMcpToolHandler.class);

    /**
     * Handle a tool call from MCP client.
     *
     * @param spec    the service method specification
     * @param request the MCP call request with arguments
     * @return the MCP call result with text content
     */
    public static McpSchema.CallToolResult handleToolCall(ServiceMethodSpec spec, McpSchema.CallToolRequest request) {
        try {
            Map<String, Object> arguments = request.arguments();

            // Build Jaws Request
            DefaultRequest jawsRequest = new DefaultRequest();
            jawsRequest.setInterfaceName(spec.getInterfaceName());
            jawsRequest.setMethodName(spec.getMethodName());
            jawsRequest.setParamDesc(buildParametersDesc(spec.getParameterTypes()));

            // Convert arguments using shared ArgumentConverter
            Object[] args = ArgumentConverter.convertArguments(spec.getParameters(), arguments);
            jawsRequest.setArguments(args);

            // Invoke provider
            Provider<?> provider = spec.getProvider();
            Response response = provider.call(jawsRequest);

            // Convert response to CallToolResult
            if (response instanceof DefaultResponse dr) {
                if (dr.getException() != null) {
                    return createErrorResult(dr.getException());
                }
                Object value = dr.getValue();
                return createSuccessResult(value);
            }

            if (response.getException() != null) {
                return createErrorResult(response.getException());
            }
            return createSuccessResult(null);

        } catch (Exception e) {
            log.error("[JawsMcp] Tool call failed: {}", spec.getActionName(), e);
            return createErrorResult(e);
        }
    }

    private static String buildParametersDesc(String[] parameterTypes) {
        if (parameterTypes == null || parameterTypes.length == 0) {
            return ReflectUtils.EMPTY_PARAM;
        }
        return String.join(ReflectUtils.PARAM_CLASS_SPLIT, parameterTypes);
    }

    private static McpSchema.CallToolResult createSuccessResult(Object value) {
        String text;
        if (value == null) {
            text = "null";
        } else if (value instanceof String s) {
            text = s;
        } else {
            text = JSON.toJSONString(value);
        }

        return McpSchema.CallToolResult.builder()
                .addTextContent(text)
                .isError(false)
                .build();
    }

    private static McpSchema.CallToolResult createErrorResult(Throwable error) {
        String message = error.getMessage() != null ? error.getMessage() : error.getClass().getName();
        return McpSchema.CallToolResult.builder()
                .addTextContent("Error: " + message)
                .isError(true)
                .build();
    }
}
