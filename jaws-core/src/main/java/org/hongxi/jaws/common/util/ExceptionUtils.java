package org.hongxi.jaws.common.util;

import org.hongxi.jaws.exception.JawsAbstractException;
import org.hongxi.jaws.exception.JawsBizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;

/**
 * Static helpers for classifying jaws exceptions, converting provider-side
 * exceptions into consumer-safe forms, and masking server-side stack traces
 * before they are serialized back to consumers.
 * <p>
 * {@link #setMockStackTrace} replaces the original stack with a fixed
 * {@link #REMOTE_MOCK_STACK} element so implementation details of the
 * provider are not leaked.
 * <p>
 * Created by shenhongxi on 2020/7/26.
 */
public class ExceptionUtils {
    private static final Logger log = LoggerFactory.getLogger(ExceptionUtils.class);

    public static final StackTraceElement[] REMOTE_MOCK_STACK = new StackTraceElement[]{
            new StackTraceElement("remoteClass", "remoteMethod", "remoteFile", 1)};

    public static boolean isBizException(Throwable t) {
        return t instanceof JawsBizException;
    }

    public static boolean isJawsException(Throwable t) {
        return t instanceof JawsAbstractException;
    }

    /**
     * Replace the stack trace of the given exception with a mock one
     * to hide server-side stack details from the consumer.
     */
    public static void setMockStackTrace(Throwable e) {
        if (e != null) {
            try {
                e.setStackTrace(REMOTE_MOCK_STACK);
            } catch (Exception e1) {
                log.warn("Failed to replace remote exception stack: {}", e1.getMessage());
            }
        }
    }

    /**
     * Convert a provider-side exception into a consumer-safe form.
     * <p>
     * An exception is passed through as-is only when its class is guaranteed
     * to be resolvable on the consumer side:
     * <ul>
     *   <li>it is declared in the invoked method signature ({@code throws})</li>
     *   <li>it is a JDK standard exception ({@code java.*} / {@code javax.*})</li>
     *   <li>it is loaded by the same class loader as the service interface</li>
     *   <li>it is a Jaws framework exception</li>
     * </ul>
     * Otherwise the exception is converted to a {@link RuntimeException} whose
     * message carries the original class name, message and stack trace as text,
     * so diagnostic information is preserved without requiring the exception
     * class to exist in the consumer's class loader.
     *
     * @param throwable      the exception thrown by the service implementation
     * @param method         the invoked service method
     * @param interfaceClass the service interface class, may be null
     * @return an exception safe to serialize back to the consumer
     */
    public static Exception toSerializableException(Throwable throwable, Method method, Class<?> interfaceClass) {
        if (throwable instanceof Exception e) {
            if (method != null) {
                for (Class<?> declaredType : method.getExceptionTypes()) {
                    if (declaredType.isInstance(e)) {
                        return e;
                    }
                }
            }
            String className = e.getClass().getName();
            if (className.startsWith("java.") || className.startsWith("javax.")) {
                return e;
            }
            if (e instanceof JawsAbstractException) {
                return e;
            }
            if (interfaceClass != null && e.getClass().getClassLoader() == interfaceClass.getClassLoader()) {
                return e;
            }
        }
        return new RuntimeException(toString(throwable));
    }

    /**
     * Render the given throwable as a string containing its class name,
     * message and full stack trace. Used to transfer exception information
     * to the consumer without depending on the throwable's class.
     */
    public static String toString(Throwable throwable) {
        StringWriter writer = new StringWriter();
        PrintWriter printer = new PrintWriter(writer);
        printer.print(throwable.getClass().getName());
        if (throwable.getMessage() != null) {
            printer.print(": " + throwable.getMessage());
        }
        printer.println();
        for (StackTraceElement element : throwable.getStackTrace()) {
            printer.println("\tat " + element);
        }
        printer.flush();
        return writer.toString();
    }
}
