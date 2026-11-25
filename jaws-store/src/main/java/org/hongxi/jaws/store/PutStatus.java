package org.hongxi.jaws.store;

/**
 * Outcome of an append. PUT_OK under async flush means "in page cache";
 * under sync flush it means "fsynced via group commit".
 */
public enum PutStatus {
    PUT_OK,
    MESSAGE_SIZE_EXCEEDED,
    CREATE_FILE_FAILED,
    FLUSH_TIMEOUT,
    OS_PAGE_CACHE_BUSY,
    UNKNOWN_ERROR
}