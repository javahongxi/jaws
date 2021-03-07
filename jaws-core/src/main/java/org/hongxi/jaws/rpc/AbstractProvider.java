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
    protected Class<T> clz;
    protected URL url;
    protected boolean alive = false;
    protected boolean close = false;

    protected Map<String, Method> methodMap = new HashMap<>();

    public AbstractProvider(URL url, Class<T> clz) {
        this.url = url;
        this.clz = clz;

        initMethodMap(clz);
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
        return clz;
    }

    @Override
    public Method lookupMethod(String methodName, String methodDesc) {
        Method method;
        String fullMethodName = ReflectUtils.getMethodDesc(methodName, methodDesc);
        method = methodMap.get(fullMethodName);
        if (method == null && StringUtils.isBlank(methodDesc)) {
            method = methodMap.get(methodName);
            if (method == null) {
                method = methodMap.get(methodName.substring(0, 1).toLowerCase() + methodName.substring(1));
            }
        }

        return method;
    }

    private void initMethodMap(Class<T> clz) {
        Method[] methods = clz.getMethods();

        List<String> dupList = new ArrayList<>();
        for (Method method : methods) {
            String methodDesc = ReflectUtils.getMethodDesc(method);
            methodMap.put(methodDesc, method);
            if (methodMap.get(method.getName()) == null) {
                methodMap.put(method.getName(), method);
            } else {
                dupList.add(method.getName());
            }
        }
        if (!dupList.isEmpty()) {
            for (String removedName : dupList) {
                methodMap.remove(removedName);
            }
        }
    }

}