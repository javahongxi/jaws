package org.hongxi.jaws.store;

/**
 * Outcome of an admin message lookup. NOT_FOUND is not an error — it simply
 * means no valid record begins at the requested physical offset.
 */
public enum ViewStatus {
    FOUND,
    NOT_FOUND
}
