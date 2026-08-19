package org.hongxi.jaws.transport;

import org.hongxi.jaws.common.util.GenericUtils;
import org.hongxi.jaws.common.util.ReflectUtils;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.rpc.*;
import org.hongxi.jaws.common.util.JawsFrameworkUtils;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

/**
 * Handler for generic RPC invocations.
 * <p>
 * Converts Map arguments to actual POJO types before invocation,
 * and converts the result back to Map/simple types after invocation.
 */
public class GenericRequestHandler extends AbstractRequestHandler {

    @Override
    protected CompletableFuture<Object> doHandleAsync(Request request, Provider<?> provider, Method method) {
        if (method == null) {
            JawsServiceException exception = new JawsServiceException(
                    "Generic invocation: method not found: " + request.getInterfaceName() + "."
                            + request.getMethodName() + "(" + request.getParamDesc() + ")");
            DefaultResponse response = JawsFrameworkUtils.buildErrorResponse(request, exception);
            response.setSerializationNumber(request.getSerializationNumber());
            return CompletableFuture.completedFuture(response);
        }

        // Convert arguments from Map to actual POJO types
        Class<?>[] paramTypes = method.getParameterTypes();
        Object[] originalArgs = request.getArguments();
        Object[] convertedArgs = new Object[paramTypes.length];
        if (originalArgs != null) {
            for (int i = 0; i < paramTypes.length && i < originalArgs.length; i++) {
                convertedArgs[i] = GenericUtils.convertArgument(originalArgs[i], paramTypes[i]);
            }
        }

        // Update the request with converted arguments and real parameter description
        if (request instanceof DefaultRequest dr) {
            dr.setArguments(convertedArgs);
            dr.setParamDesc(ReflectUtils.getMethodParamDesc(method));
        }

        return callAsync(request, provider).thenApply(response -> {
            // Convert the result for generic response
            if (response.getException() == null && response.getValue() != null) {
                Object convertedResult = GenericUtils.convertResult(response.getValue());
                if (response instanceof DefaultResponse dr) {
                    dr.setValue(convertedResult);
                }
            }
            response.setSerializationNumber(request.getSerializationNumber());
            return response;
        });
    }
}
