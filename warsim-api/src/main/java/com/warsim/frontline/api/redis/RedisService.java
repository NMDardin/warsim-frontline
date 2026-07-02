package com.warsim.frontline.api.redis;

import java.util.concurrent.CompletableFuture;

public interface RedisService extends NodeDirectoryService, ControlMessageBus, AutoCloseable {
    boolean enabled();
    String sanitizedAddress();
    String namespace();
    RedisState state();
    RedisHealth health();
    RedisMetricsSnapshot metrics();
    CompletableFuture<Void> start();
    CompletableFuture<Void> publishHeartbeat(NodeSnapshot snapshot);
    @Override void close();
}
