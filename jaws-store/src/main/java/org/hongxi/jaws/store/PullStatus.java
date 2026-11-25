package org.hongxi.jaws.store;

/**
 * Outcome of a pull. NO_NEW_MSG is the normal long-polling timeout answer;
 * OFFSET_ILLEGAL means the requested offset is outside the queue range.
 */
public enum PullStatus {
    FOUND,
    NO_NEW_MSG,
    OFFSET_ILLEGAL
}