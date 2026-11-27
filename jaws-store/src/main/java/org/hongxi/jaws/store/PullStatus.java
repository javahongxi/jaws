package org.hongxi.jaws.store;

/**
 * Outcome of a pull. NO_NEW_MSG is the normal long-polling timeout answer;
 * OFFSET_ILLEGAL means the requested offset is outside the queue range;
 * NOT_LEASED means the caller does not hold the queue's live lease — it either
 * never attached or a second consumer has taken the queue over.
 */
public enum PullStatus {
    FOUND,
    NO_NEW_MSG,
    OFFSET_ILLEGAL,
    NOT_LEASED
}