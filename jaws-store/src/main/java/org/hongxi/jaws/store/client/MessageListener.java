package org.hongxi.jaws.store.client;

import org.hongxi.jaws.store.MessageView;

import java.util.List;

/**
 * Batch consumption callback. The consumer advances and persists the queue
 * offset only after this method returns normally; throwing leaves the offset
 * unchanged (the batch is not redelivered in this thin client — at-most-once
 * per batch, a declared simplification vs RocketMQ's retry machinery).
 */
@FunctionalInterface
public interface MessageListener {

    void onMessage(List<MessageView> messages);
}