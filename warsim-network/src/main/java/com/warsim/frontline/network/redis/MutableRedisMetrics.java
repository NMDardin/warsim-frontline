package com.warsim.frontline.network.redis;

import com.warsim.frontline.api.redis.RedisMetricsSnapshot;
import com.warsim.frontline.api.redis.RedisState;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

final class MutableRedisMetrics {
    final AtomicLong reconnects = new AtomicLong();
    final AtomicLong successfulHeartbeats = new AtomicLong();
    final AtomicLong failedHeartbeats = new AtomicLong();
    final AtomicReference<Instant> lastHeartbeat = new AtomicReference<>();
    final AtomicInteger discoveredNodes = new AtomicInteger();
    final AtomicLong published = new AtomicLong();
    final AtomicLong consumed = new AtomicLong();
    final AtomicLong acknowledged = new AtomicLong();
    final AtomicLong retried = new AtomicLong();
    final AtomicLong deadLetter = new AtomicLong();
    final AtomicLong duplicates = new AtomicLong();
    final AtomicLong expired = new AtomicLong();
    final AtomicLong invalid = new AtomicLong();
    final AtomicLong pending = new AtomicLong();
    final AtomicLong inFlight = new AtomicLong();

    RedisMetricsSnapshot snapshot(RedisState state, boolean connected) {
        return new RedisMetricsSnapshot(
            state, connected, reconnects.get(), successfulHeartbeats.get(), failedHeartbeats.get(),
            lastHeartbeat.get(), discoveredNodes.get(), published.get(), consumed.get(),
            acknowledged.get(), retried.get(), deadLetter.get(), duplicates.get(), expired.get(),
            invalid.get(), pending.get(), inFlight.get()
        );
    }
}
