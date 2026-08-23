package org.hongxi.jaws.mcp;

import com.alibaba.fastjson2.JSON;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.hongxi.jaws.mcp.bridge.ArgumentConverter;
import org.hongxi.jaws.mcp.bridge.JsonSchemaGenerator;
import org.hongxi.jaws.mcp.bridge.ServiceMethodSpec;
import org.hongxi.jaws.common.util.ReflectUtils;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.DefaultResponse;
import org.hongxi.jaws.rpc.Provider;
import org.hongxi.jaws.rpc.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Servlet that exposes Jaws RPC services via REST API.
 * <p>
 * Endpoints:
 * <ul>
 *   <li>GET /services - List all registered services and their methods</li>
 *   <li>GET /services/{interfaceName} - Get methods for a specific service</li>
 *   <li>POST /invoke/{interfaceName}/{methodName} - Invoke a service method</li>
 * </ul>
 * <p>
 * Example usage:
 * <pre>
 * // List services
 * GET /rest/services
 *
 * // Get service methods
 * GET /rest/services/org.hongxi.jaws.sample.api.DemoService
 *
 * // Invoke method
 * POST /rest/invoke/org.hongxi.jaws.sample.api.DemoService/hello
 * Content-Type: application/json
 * {"arg0": "World"}
 * </pre>
 *
 * @author shenhongxi
 */
public class RestInvokeServlet extends HttpServlet {

    private static final Logger log = LoggerFactory.getLogger(RestInvokeServlet.class);

    private static final String CONTENT_TYPE_JSON = "application/json;charset=UTF-8";

    /**
     * Map of interfaceName -> list of ServiceMethodSpec
     */
    private final Map<String, List<ServiceMethodSpec>> serviceRegistry = new ConcurrentHashMap<>();

    /**
     * Map of actionName -> ServiceMethodSpec (for quick lookup during invoke)
     */
    private final Map<String, ServiceMethodSpec> actionRegistry = new ConcurrentHashMap<>();

    /**
     * Register a service interface and its methods.
     *
     * @param interfaceClass the service interface class
     * @param provider       the Jaws provider that can execute methods
     */
    public void registerService(Class<?> interfaceClass, Provider<?> provider) {
        String interfaceName = interfaceClass.getName();
        List<ServiceMethodSpec> specs = createMethodSpecs(interfaceClass, provider);

        serviceRegistry.put(interfaceName, specs);
        for (ServiceMethodSpec spec : specs) {
            actionRegistry.put(spec.getActionName(), spec);
        }

        log.info("[JawsRest] Registered service: {} with {} methods", interfaceName, specs.size());
    }

    /**
     * Unregister a service.
     *
     * @param interfaceName the fully qualified interface name
     */
    public void unregisterService(String interfaceName) {
        List<ServiceMethodSpec> specs = serviceRegistry.remove(interfaceName);
        if (specs != null) {
            for (ServiceMethodSpec spec : specs) {
                actionRegistry.remove(spec.getActionName());
            }
            log.info("[JawsRest] Unregistered service: {}", interfaceName);
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType(CONTENT_TYPE_JSON);
        String pathInfo = req.getPathInfo();
        if (pathInfo == null) {
            pathInfo = "";
        }

        try {
            if (pathInfo.equals("") || pathInfo.equals("/")) {
                // GET /rest/services - list all services
                handleListServices(resp);
            } else if (pathInfo.startsWith("/services/")) {
                // GET /rest/services/{interfaceName}
                String interfaceName = pathInfo.substring("/services/".length());
                handleGetService(interfaceName, resp);
            } else if (pathInfo.equals("/services")) {
                handleListServices(resp);
            } else {
                sendError(resp, HttpServletResponse.SC_NOT_FOUND, "Unknown endpoint: " + pathInfo);
            }
        } catch (Exception e) {
            log.error("[JawsRest] Error handling GET request", e);
            sendError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType(CONTENT_TYPE_JSON);
        String pathInfo = req.getPathInfo();
        if (pathInfo == null) {
            pathInfo = "";
        }

        try {
            if (pathInfo.startsWith("/invoke/")) {
                // POST /rest/invoke/{interfaceName}/{methodName}
                String remaining = pathInfo.substring("/invoke/".length());
                handleInvoke(remaining, req, resp);
            } else {
                sendError(resp, HttpServletResponse.SC_NOT_FOUND, "Unknown endpoint: " + pathInfo);
            }
        } catch (Exception e) {
            log.error("[JawsRest] Error handling POST request", e);
            sendError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    private void handleListServices(HttpServletResponse resp) throws IOException {
        List<Map<String, Object>> services = new ArrayList<>();
        for (Map.Entry<String, List<ServiceMethodSpec>> entry : serviceRegistry.entrySet()) {
            Map<String, Object> serviceInfo = new LinkedHashMap<>();
            serviceInfo.put("interfaceName", entry.getKey());
            serviceInfo.put("methodCount", entry.getValue().size());

            List<Map<String, Object>> methods = new ArrayList<>();
            for (ServiceMethodSpec spec : entry.getValue()) {
                methods.add(buildMethodInfo(spec));
            }
            serviceInfo.put("methods", methods);
            services.add(serviceInfo);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("services", services);
        result.put("totalServices", services.size());

        resp.setStatus(HttpServletResponse.SC_OK);
        JSON.writeTo(resp.getOutputStream(), result);
    }

    private void handleGetService(String interfaceName, HttpServletResponse resp) throws IOException {
        List<ServiceMethodSpec> specs = serviceRegistry.get(interfaceName);
        if (specs == null) {
            sendError(resp, HttpServletResponse.SC_NOT_FOUND, "Service not found: " + interfaceName);
            return;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("interfaceName", interfaceName);

        List<Map<String, Object>> methods = new ArrayList<>();
        for (ServiceMethodSpec spec : specs) {
            methods.add(buildMethodInfo(spec));
        }
        result.put("methods", methods);

        resp.setStatus(HttpServletResponse.SC_OK);
        JSON.writeTo(resp.getOutputStream(), result);
    }

    private void handleInvoke(String path, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        // Parse path: {interfaceName}/{methodName}
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash <= 0) {
            sendError(resp, HttpServletResponse.SC_BAD_REQUEST, "Invalid invoke path. Expected: /invoke/{interfaceName}/{methodName}");
            return;
        }

        String interfaceName = path.substring(0, lastSlash);
        String methodName = path.substring(lastSlash + 1);

        // Find matching method spec
        ServiceMethodSpec spec = findMethodSpec(interfaceName, methodName);
        if (spec == null) {
            sendError(resp, HttpServletResponse.SC_NOT_FOUND,
                    "Method not found: " + interfaceName + "." + methodName);
            return;
        }

        // Read request body
        Map<String, Object> arguments = readRequestBody(req);

        // Convert arguments using shared ArgumentConverter
        Object[] args = ArgumentConverter.convertArguments(spec.getParameters(), arguments);

        // Build Jaws Request
        DefaultRequest jawsRequest = new DefaultRequest();
        jawsRequest.setInterfaceName(spec.getInterfaceName());
        jawsRequest.setMethodName(spec.getMethodName());
        jawsRequest.setParamDesc(buildParametersDesc(spec.getParameterTypes()));
        jawsRequest.setArguments(args);

        // Invoke provider
        Provider<?> provider = spec.getProvider();
        Response response = provider.call(jawsRequest);

        // Build response
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("actionName", spec.getActionName());
        result.put("interfaceName", spec.getInterfaceName());
        result.put("methodName", spec.getMethodName());

        if (response.getException() != null) {
            result.put("success", false);
            result.put("error", response.getException().getMessage());
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        } else {
            result.put("success", true);
            if (response instanceof DefaultResponse dr) {
                result.put("data", dr.getValue());
            }
            resp.setStatus(HttpServletResponse.SC_OK);
        }

        JSON.writeTo(resp.getOutputStream(), result);
    }

    private ServiceMethodSpec findMethodSpec(String interfaceName, String methodName) {
        List<ServiceMethodSpec> specs = serviceRegistry.get(interfaceName);
        if (specs == null) {
            return null;
        }

        // Try exact match first (actionName = InterfaceName_methodName)
        String simpleInterfaceName = interfaceName.substring(interfaceName.lastIndexOf('.') + 1);
        String actionName = simpleInterfaceName + "_" + methodName;

        ServiceMethodSpec spec = actionRegistry.get(actionName);
        if (spec != null) {
            return spec;
        }

        // Try finding by interface + method name (for overloaded methods, return first match)
        for (ServiceMethodSpec s : specs) {
            if (s.getMethodName().equals(methodName)) {
                return s;
            }
        }

        return null;
    }

    private Map<String, Object> buildMethodInfo(ServiceMethodSpec spec) {
        Map<String, Object> methodInfo = new LinkedHashMap<>();
        methodInfo.put("actionName", spec.getActionName());
        methodInfo.put("methodName", spec.getMethodName());

        // Build parameter info
        List<Map<String, Object>> params = new ArrayList<>();
        Parameter[] parameters = spec.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            Map<String, Object> paramInfo = new LinkedHashMap<>();
            Parameter param = parameters[i];
            String paramName = param.isNamePresent() ? param.getName() : "arg" + i;
            paramInfo.put("name", paramName);
            paramInfo.put("type", spec.getParameterTypes()[i]);
            paramInfo.put("javaType", param.getType().getName());
            params.add(paramInfo);
        }
        methodInfo.put("parameters", params);

        // Generate JSON Schema for parameters
        Map<String, Object> inputSchema = JsonSchemaGenerator.generateMethodSchema(parameters);
        methodInfo.put("inputSchema", inputSchema);

        // Return type
        Class<?> returnType = spec.getMethod().getReturnType();
        methodInfo.put("returnType", returnType.getName());

        return methodInfo;
    }

    private Map<String, Object> readRequestBody(HttpServletRequest req) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = req.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }

        String body = sb.toString().trim();
        if (body.isEmpty()) {
            return Collections.emptyMap();
        }

        return JSON.parseObject(body, Map.class);
    }

    private String buildParametersDesc(String[] parameterTypes) {
        if (parameterTypes == null || parameterTypes.length == 0) {
            return ReflectUtils.EMPTY_PARAM;
        }
        return String.join(ReflectUtils.PARAM_CLASS_SPLIT, parameterTypes);
    }

    private void sendError(HttpServletResponse resp, int status, String message) throws IOException {
        resp.setStatus(status);
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("success", false);
        error.put("error", message);
        JSON.writeTo(resp.getOutputStream(), error);
    }

    /**
     * Create service method specifications from a service interface and its Provider.
     * This is a convenience method that delegates to the shared logic.
     */
    private static List<ServiceMethodSpec> createMethodSpecs(Class<?> interfaceClass, Provider<?> provider) {
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

        Map<String, Integer> methodNameIndex = new HashMap<>();
        for (Method method : methods) {
            if (!Modifier.isPublic(method.getModifiers()) || method.getDeclaringClass() == Object.class) {
                continue;
            }

            String methodName = method.getName();
            int index = methodNameIndex.merge(methodName, 0, Integer::sum) + 1;
            methodNameIndex.put(methodName, index);

            String actionName;
            int totalCount = methodNameCount.getOrDefault(methodName, 1);
            if (totalCount > 1) {
                actionName = simpleInterfaceName + "_" + methodName + "_" + index;
            } else {
                actionName = simpleInterfaceName + "_" + methodName;
            }

            Class<?>[] paramTypes = method.getParameterTypes();
            String[] paramTypeNames = new String[paramTypes.length];
            for (int i = 0; i < paramTypes.length; i++) {
                paramTypeNames[i] = ReflectUtils.getName(paramTypes[i]);
            }

            specs.add(new ServiceMethodSpec(
                    actionName,
                    interfaceClass.getName(),
                    methodName,
                    paramTypeNames,
                    method,
                    provider,
                    method.getParameters()
            ));
        }

        return specs;
    }
}
