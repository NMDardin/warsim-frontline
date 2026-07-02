package com.warsim.frontline.api.redis;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public interface ControlMessageBus {
    CompletableFuture<String> publish(ControlMessage message);
    void registerHandler(ControlMessageType type, ControlMessageHandler handler);
    CompletableFuture<Duration> ping(String targetNodeId, Duration timeout);
}
