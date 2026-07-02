package com.warsim.frontline.api.redis;

import java.util.concurrent.CompletableFuture;

@FunctionalInterface
public interface ControlMessageHandler {
    CompletableFuture<ControlMessageResult> handle(ControlMessage message);
}
