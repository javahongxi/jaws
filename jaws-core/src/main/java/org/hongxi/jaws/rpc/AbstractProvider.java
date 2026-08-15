package org.hongxi.jaws.rpc;

import org.apache.commons.lang3.StringUtils;
import org.hongxi.jaws.common.util.ReflectUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by shenhongxi on 2021/3/7.
 */
public abstract class AbstractProvider<T> implements Provider<T> {
    protected Class<T> interfaceClass;
    protected URL url;
    protected boolean alive = false;
    protected boolean close = false;

    protected Map<String, Method> methodMap = new HashMap<>();

    public AbstractProvider(Class<T> interfaceClass, URL url) {
        this.interfaceClass = interfaceClass;
        this.url = url;

        initMethodMap(interfaceClass);
    }

    @Override
    public Response call(Request request) {
        return invoke(request);
    }

    protected abstract Response invoke(Request request);

    @Override
    public void init() {
        alive = true;
    }

    @Override
    public void destroy() {
        alive = false;
        close = true;
    }

    @Override
    public boolean isAvailable() {
        return alive;
    }

    @Override
    public String desc() {
        if (url != null) {
            return url.toString();
        }

        return null;
    }

    @Override
    public URL getUrl() {
        return url;
    }

    @Override
    public Class<T> getInterface() {
        return interfaceClass;
    }

    @Override
    public Method lookupMethod(String methodName, String paramDesc) {
        String methodDesc = ReflectUtils.getMethodDesc(methodName, paramDesc);
        Method method = methodMap.get(methodDesc);
        if (method == null && StringUtils.isBlank(paramDesc)) {
            method = methodMap.get(methodName);
        }
        return method;
    }

    /**
     * Build the method routing table. Supports exact signature matching for all methods,
     * and shorthand name matching for non-overloaded methods. Overloaded methods require
     * the full parameter descriptor for disambiguation.
     */
    private void initMethodMap(Class<T> interfaceClass) {
        Method[] methods = interfaceClass.getMethods();

        List<String> overloadedMethodNames = new ArrayList<>();
        for (Method method : methods) {
            String methodDesc = ReflectUtils.getMethodDesc(method);
            methodMap.put(methodDesc, method);
            if (methodMap.get(method.getName()) == null) {
                methodMap.put(method.getName(), method);
            } else {
                overloadedMethodNames.add(method.getName());
            }
        }
        if (!overloadedMethodNames.isEmpty()) {
            for (String removedName : overloadedMethodNames) {
                methodMap.remove(removedName);
            }
        }
    }
}