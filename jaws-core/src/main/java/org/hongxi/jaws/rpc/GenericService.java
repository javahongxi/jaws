package org.hongxi.jaws.rpc;

/**
 * Generic service interface for invocation without depending on the actual service interface JAR.
 * <p>
 * This is essential for gateway scenarios, testing platforms, and cross-language integration
 * where the consumer does not have access to the provider's interface definitions.
 * <p>
 * Usage on consumer side:
 * <pre>{@code
 * ReferenceConfig<GenericService> ref = new ReferenceConfig<>();
 * ref.setInterface(GenericService.class);
 * ref.setServiceInterface("com.example.DemoService");
 * ref.setGeneric(true);
 * GenericService service = ref.getRef();
 * Object result = service.$invoke("sayHello",
 *     new String[]{"java.lang.String"},
 *     new Object[]{"world"});
 * }</pre>
 * <p>
 * For complex POJO parameters, use {@code Map<String, Object>} to represent the object fields:
 * <pre>{@code
 * Map<String, Object> user = new HashMap<>();
 * user.put("name", "john");
 * user.put("age", 25);
 * service.$invoke("createUser",
 *     new String[]{"com.example.User"},
 *     new Object[]{user});
 * }</pre>
 * <p>
 * Created by shenhongxi on 2026/7/18.
 */
public interface GenericService {

    /**
     * Generic invocation method.
     *
     * @param method         the real method name to invoke on the provider
     * @param parameterTypes the fully qualified class names of the method parameter types
     * @param args           the arguments. For primitive/String types, pass directly.
     *                       For complex POJOs, use {@code Map<String, Object>} where keys are field names.
     * @return the invocation result
     */
    Object $invoke(String method, String[] parameterTypes, Object[] args);
}
