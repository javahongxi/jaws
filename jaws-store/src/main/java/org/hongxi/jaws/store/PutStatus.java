package org.hongxi.jaws.store;

/**
 * Outcome of an append. PUT_OK under async flush means "in page cache";
 * under sync flush it means "fsynced via group commit". SERVICE_NOT_AVAILABLE
 * means the record never reached the log: the store refused the append because its
 * index dispatch is fused, so nothing was written and the caller may retry later.
 */
public enum PutStatus {
    PUT_OK,
    MESSAGE_SIZE_EXCEEDED,
    CREATE_FILE_FAILED,
    FLUSH_TIMEOUT,
    OS_PAGE_CACHE_BUSY,
    SERVICE_NOT_AVAILABLE,
    UNKNOWN_ERROR
}