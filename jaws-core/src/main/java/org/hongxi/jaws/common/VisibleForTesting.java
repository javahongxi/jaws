package org.hongxi.jaws.common;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a member that is widened beyond its natural use only so tests can reach
 * it — production code must not come to depend on it.
 * <p>
 * It is a compile-time marker: it changes no access level and is not visible to
 * runtime reflection, so it documents intent (and can back a static check)
 * without becoming part of the runtime contract.
 *
 * @author shenhongxi
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD, ElementType.TYPE})
public @interface VisibleForTesting {
}
