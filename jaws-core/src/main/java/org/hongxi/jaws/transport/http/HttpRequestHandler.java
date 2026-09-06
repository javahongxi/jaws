package org.hongxi.jaws.transport.http;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.QueryStringDecoder;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.DefaultResponse;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.RpcContext;
import org.hongxi.jaws.transport.MessageHandler;
import org.hongxi.jaws.transport.http.rest.ParameterBinding;
import org.hongxi.jaws.transport.http.rest.RestMapping;
import org.hongxi.jaws.transport.http.rest.RestMappingRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * Inbound HTTP/1.1 request handler for the HTTP transport.
 * <p>
 * Routes:
 * <ul>
 *   <li>{@code GET /health} — returns 200 OK with body "OK", no RPC dispatch</li>
 *   <li>{@code POST /invoke} — JSON body dispatched to the Jaws
 *       {@link MessageHandler} pipeline on the business executor</li>
 * </ul>
 * <p>
 * Request JSON format:
 * <pre>{@code
 * {
 *   "interface": "org.hongxi.jaws.sample.api.DemoService",
 *   "method": "hello",
 *   "group": "test",           // optional, defaults to URL group
 *   "version": "2.0",          // optional, defaults to URL version
 *   "args": ["lily"]           // optional, JSON array of arguments
 * }
 * }</pre>
 * <p>
 * Response JSON format:
 * <pre>{@code
 * {
 *   "value": "hello lily",     // null if exception occurred
 *   "error": null,             // error message string if exception occurred
 *   "processTime": 12          // server-side processing time in ms
 * }
 * }</pre>
 * <p>
 * All processing happens off the event loop so IO threads are never blocked.
 *
 * @author shenhongxi
 */
public class HttpRequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final Logger log = LoggerFactory.getLogger(HttpRequestHandler.class);

    private static final String INVOKE_PATH = "/invoke";
    private static final String HEALTH_PATH = "/health";
    private static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";

    private final MessageHandler messageHandler;
    private final ExecutorService serverExecutor;
    private final Map<String, Class<?>> interfaceClasses;
    private final RestMappingRegistry restMappingRegistry;

    public HttpRequestHandler(MessageHandler messageHandler,
                              ExecutorService serverExecutor,
                              Map<String, Class<?>> interfaceClasses,
                              RestMappingRegistry restMappingRegistry) {
        this.messageHandler = messageHandler;
        this.serverExecutor = serverExecutor;
        this.interfaceClasses = interfaceClasses;
        this.restMappingRegistry = restMappingRegistry;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        String uri = request.uri();

        // Health check: GET /health
        if (HEALTH_PATH.equals(uri) || uri.startsWith(HEALTH_PATH + "?")) {
            if (!HttpMethod.GET.equals(request.method())) {
                sendHttpResponse(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED,
                        errorJson("Only GET is supported for /health"));
                return;
            }
            sendHealthResponse(ctx);
            return;
        }

        // REST mapping: annotation-driven routes
        if (restMappingRegistry != null && !restMappingRegistry.isEmpty()) {
            String path = extractPath(uri);
            Optional<RestMappingRegistry.MatchedMapping> matched =
                    restMappingRegistry.match(request.method(), path);
            if (matched.isPresent()) {
                handleRestRequest(ctx, request, matched.get(), uri);
                return;
            }
        }

        // RPC invoke: POST /invoke
        if (!INVOKE_PATH.equals(uri) && !uri.startsWith(INVOKE_PATH + "?")) {
            sendHttpResponse(ctx, HttpResponseStatus.NOT_FOUND,
                    errorJson("Unknown endpoint: " + uri + ". Use POST /invoke or GET /health"));
            return;
        }
        if (!HttpMethod.POST.equals(request.method())) {
            sendHttpResponse(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED,
                    errorJson("Only POST is supported for /invoke"));
            return;
        }

        // Parse JSON body
        String body = request.content().toString(StandardCharsets.UTF_8);
        JSONObject json;
        try {
            json = JSON.parseObject(body);
        } catch (Exception e) {
            sendHttpResponse(ctx, HttpResponseStatus.BAD_REQUEST,
                    errorJson("Invalid JSON: " + e.getMessage()));
            return;
        }

        String interfaceName = json.getString("interface");
        String methodName = json.getString("method");
        if (interfaceName == null || interfaceName.isEmpty()) {
            sendHttpResponse(ctx, HttpResponseStatus.BAD_REQUEST,
                    errorJson("'interface' is required"));
            return;
        }
        if (methodName == null || methodName.isEmpty()) {
            sendHttpResponse(ctx, HttpResponseStatus.BAD_REQUEST,
                    errorJson("'method' is required"));
            return;
        }

        String group = json.getString("group");
        String version = json.getString("version");
        Object[] args = convertArgs(json, interfaceName, methodName);

        // Build DefaultRequest
        DefaultRequest rpcRequest = new DefaultRequest();
        rpcRequest.setInterfaceName(interfaceName);
        rpcRequest.setMethodName(methodName);
        rpcRequest.setArguments(args);
        if (group != null) {
            rpcRequest.setAttachment("group", group);
        }
        if (version != null) {
            rpcRequest.setAttachment("version", version);
        }

        // Dispatch on business thread pool
        dispatch(ctx, rpcRequest);
    }

    /**
     * Handle a REST-mapped request: extract parameters from path variables,
     * query string, and JSON body, then dispatch to the RPC pipeline.
     */
    private void handleRestRequest(ChannelHandlerContext ctx, FullHttpRequest request,
                                   RestMappingRegistry.MatchedMapping matched, String uri) {
        RestMapping mapping = matched.getMapping();
        Map<String, String> pathVariables = matched.getPathVariables();
        Method javaMethod = mapping.getJavaMethod();
        List<ParameterBinding> bindings = mapping.getParameterBindings();

        // Parse query string
        QueryStringDecoder queryDecoder = new QueryStringDecoder(uri);
        Map<String, List<String>> queryParams = queryDecoder.parameters();

        // Parse JSON body if present (for POST/PUT/PATCH with REQUEST_BODY binding)
        JSONObject jsonBody = null;
        boolean hasRequestBody = bindings.stream()
                .anyMatch(b -> b.getSource() == ParameterBinding.Source.REQUEST_BODY);
        if (hasRequestBody && request.content().readableBytes() > 0) {
            String body = request.content().toString(StandardCharsets.UTF_8);
            try {
                jsonBody = JSON.parseObject(body);
            } catch (Exception e) {
                sendHttpResponse(ctx, HttpResponseStatus.BAD_REQUEST,
                        errorJson("Invalid JSON body: " + e.getMessage()));
                return;
            }
        }

        // Build arguments array
        Class<?>[] paramTypes = javaMethod.getParameterTypes();
        Object[] args = new Object[paramTypes.length];
        for (int i = 0; i < bindings.size(); i++) {
            ParameterBinding binding = bindings.get(i);
            args[i] = resolveArgument(binding, pathVariables, queryParams, jsonBody, paramTypes[i]);
        }

        // Build DefaultRequest
        DefaultRequest rpcRequest = new DefaultRequest();
        rpcRequest.setInterfaceName(mapping.getInterfaceName());
        rpcRequest.setMethodName(mapping.getMethodName());
        rpcRequest.setArguments(args);

        dispatch(ctx, rpcRequest);
    }

    /**
     * Resolve a single method argument from the appropriate HTTP source.
     */
    private static Object resolveArgument(ParameterBinding binding,
                                          Map<String, String> pathVariables,
                                          Map<String, List<String>> queryParams,
                                          JSONObject jsonBody,
                                          Class<?> targetType) {
        Object rawValue = switch (binding.getSource()) {
            case PATH_VARIABLE -> pathVariables.get(binding.getName());
            case QUERY_PARAM -> {
                List<String> values = queryParams.get(binding.getName());
                yield (values != null && !values.isEmpty()) ? values.get(0) : null;
            }
            case REQUEST_BODY -> jsonBody;
            case NONE -> null;
        };

        if (rawValue == null) {
            return getDefaultValue(targetType);
        }

        // For REQUEST_BODY, the raw value is a JSONObject — convert via JSON round-trip
        if (binding.getSource() == ParameterBinding.Source.REQUEST_BODY) {
            if (targetType.isInstance(rawValue)) {
                return rawValue;
            }
            String jsonStr = JSON.toJSONString(rawValue);
            return JSON.parseObject(jsonStr, targetType);
        }

        // For path variables and query params, the raw value is a String — convert
        return convertStringValue(rawValue.toString(), targetType);
    }

    /**
     * Convert a string value to the target type.
     */
    private static Object convertStringValue(String value, Class<?> targetType) {
        if (targetType == String.class || targetType == CharSequence.class) {
            return value;
        }
        try {
            if (targetType == int.class || targetType == Integer.class) {
                return Integer.parseInt(value);
            }
            if (targetType == long.class || targetType == Long.class) {
                return Long.parseLong(value);
            }
            if (targetType == double.class || targetType == Double.class) {
                return Double.parseDouble(value);
            }
            if (targetType == float.class || targetType == Float.class) {
                return Float.parseFloat(value);
            }
            if (targetType == short.class || targetType == Short.class) {
                return Short.parseShort(value);
            }
            if (targetType == byte.class || targetType == Byte.class) {
                return Byte.parseByte(value);
            }
            if (targetType == boolean.class || targetType == Boolean.class) {
                return Boolean.parseBoolean(value);
            }
        } catch (NumberFormatException e) {
            return getDefaultValue(targetType);
        }
        // Complex type from string: try JSON parse
        try {
            return JSON.parseObject(value, targetType);
        } catch (Exception e) {
            return getDefaultValue(targetType);
        }
    }

    /**
     * Returns the default value for a type: 0 for numeric primitives, false for boolean, null for objects.
     */
    private static Object getDefaultValue(Class<?> type) {
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0d;
        if (type == float.class) return 0.0f;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        return null;
    }

    /**
     * Extract the path portion from a URI (strip query string).
     */
    private static String extractPath(String uri) {
        int queryIndex = uri.indexOf('?');
        return queryIndex >= 0 ? uri.substring(0, queryIndex) : uri;
    }

    private void dispatch(ChannelHandlerContext ctx, Request request) {
        long startTime = System.currentTimeMillis();
        try {
            serverExecutor.execute(() -> {
                RpcContext.init(request);
                messageHandler.handleAsync(request)
                        .handle((result, throwable) -> {
                            DefaultResponse response;
                            if (throwable != null) {
                                log.error("HTTP invoke failed: {}", request, throwable);
                                response = new DefaultResponse();
                                response.setThrowable(new RuntimeException(
                                        "process request failed: " + throwable.getMessage(), throwable));
                            } else if (result instanceof DefaultResponse dr) {
                                response = dr;
                            } else if (result instanceof Response r) {
                                response = new DefaultResponse(r);
                            } else {
                                response = new DefaultResponse(result);
                            }
                            response.setRequestId(request.getRequestId());
                            response.setProcessTime(System.currentTimeMillis() - startTime);
                            return response;
                        })
                        .thenAccept(response ->
                                sendHttpResponse(ctx, HttpResponseStatus.OK, responseJson(response)))
                        .exceptionally(e -> {
                            log.error("Failed to send HTTP response: requestId={}",
                                    request.getRequestId(), e);
                            return null;
                        })
                        .whenComplete((v, e) -> RpcContext.destroy());
            });
        } catch (RejectedExecutionException e) {
            log.warn("HTTP request rejected: server thread pool is full");
            sendHttpResponse(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE,
                    errorJson("Server thread pool is full"));
        }
    }

    /**
     * Convert JSON args to the method's parameter types using reflection.
     * Falls back to raw JSON values if interface class is not registered or
     * the method cannot be resolved.
     */
    private Object[] convertArgs(JSONObject json, String interfaceName, String methodName) {
        Object[] rawArgs = json.getJSONArray("args") != null
                ? json.getJSONArray("args").toArray()
                : null;
        if (rawArgs == null || rawArgs.length == 0) {
            return rawArgs;
        }

        Class<?> interfaceClass = interfaceClasses.get(interfaceName);
        if (interfaceClass == null) {
            log.debug("Interface class not registered for HTTP type conversion: {}", interfaceName);
            return rawArgs;
        }

        Method method = findMethod(interfaceClass, methodName, rawArgs.length);
        if (method == null) {
            log.debug("Method not found for HTTP type conversion: {}.{}({} args)",
                    interfaceName, methodName, rawArgs.length);
            return rawArgs;
        }

        Class<?>[] paramTypes = method.getParameterTypes();
        Object[] converted = new Object[rawArgs.length];
        for (int i = 0; i < rawArgs.length; i++) {
            converted[i] = convertValue(rawArgs[i], paramTypes[i]);
        }
        return converted;
    }

    private static Method findMethod(Class<?> clazz, String methodName, int argCount) {
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(methodName) && m.getParameterCount() == argCount) {
                return m;
            }
        }
        return null;
    }

    /**
     * Convert a single JSON value to the target type.
     * Handles primitives, wrappers, strings, and complex objects (via JSON round-trip).
     */
    private static Object convertValue(Object value, Class<?> targetType) {
        if (value == null) {
            return null;
        }
        if (targetType.isInstance(value)) {
            return value;
        }
        // Primitives and wrappers from JSON numbers
        if (value instanceof Number number) {
            if (targetType == int.class || targetType == Integer.class) {
                return number.intValue();
            }
            if (targetType == long.class || targetType == Long.class) {
                return number.longValue();
            }
            if (targetType == double.class || targetType == Double.class) {
                return number.doubleValue();
            }
            if (targetType == float.class || targetType == Float.class) {
                return number.floatValue();
            }
            if (targetType == short.class || targetType == Short.class) {
                return number.shortValue();
            }
            if (targetType == byte.class || targetType == Byte.class) {
                return number.byteValue();
            }
            if (targetType == boolean.class || targetType == Boolean.class) {
                return number.intValue() != 0;
            }
        }
        // Boolean from JSON
        if (value instanceof Boolean b) {
            if (targetType == boolean.class || targetType == Boolean.class) {
                return b;
            }
        }
        // String / CharSequence
        if (targetType == String.class || targetType == CharSequence.class) {
            return value.toString();
        }
        // Complex object: convert via JSON round-trip
        String jsonStr = JSON.toJSONString(value);
        return JSON.parseObject(jsonStr, targetType);
    }

    private void sendHealthResponse(ChannelHandlerContext ctx) {
        byte[] body = "OK".getBytes(StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
        HttpUtil.setContentLength(response, body.length);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private void sendHttpResponse(ChannelHandlerContext ctx, HttpResponseStatus status, String jsonBody) {
        byte[] body = jsonBody.getBytes(StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, Unpooled.copiedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, JSON_CONTENT_TYPE);
        HttpUtil.setContentLength(response, body.length);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private static String responseJson(Response response) {
        JSONObject json = new JSONObject();
        json.put("value", response.getRawValue());
        Throwable t = response.getThrowable();
        if (t != null) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            json.put("error", cause.getMessage());
        } else {
            json.put("error", null);
        }
        json.put("processTime", response.getProcessTime());
        return json.toJSONString();
    }

    private static String errorJson(String message) {
        JSONObject json = new JSONObject();
        json.put("value", null);
        json.put("error", message);
        json.put("processTime", 0);
        return json.toJSONString();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("HTTP handler unexpected error", cause);
        if (ctx.channel().isActive()) {
            sendHttpResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    errorJson("Internal server error: " + cause.getMessage()));
        }
    }
}
