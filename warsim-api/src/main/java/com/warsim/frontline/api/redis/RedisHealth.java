package com.warsim.frontline.api.redis;

import java.time.Instant;
import java.util.Objects;

public record RedisHealth(RedisState state, boolean connected, Instant checkedAt, String summary) {
    public RedisHealth {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(summary, "summary");
    }

    public static RedisHealth initial(RedisState state) {
        return new RedisHealth(state, false, null, "尚未连接");
    }
}
