package com.warsim.frontline.api.redis;

import java.time.Instant;

public record RedisMetricsSnapshot(
    RedisState state,
    boolean connected,
    long reconnectCount,
    long successfulHeartbeats,
    long failedHeartbeats,
    Instant lastSuccessfulHeartbeat,
    int discoveredNodes,
    long publishedMessages,
    long consumedMessages,
    long acknowledgedMessages,
    long retriedMessages,
    long deadLetterMessages,
    long duplicateMessages,
    long expiredMessages,
    long invalidMessages,
    long pendingMessages,
    long inFlightMessages
) {
}
