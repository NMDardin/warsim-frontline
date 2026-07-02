package com.warsim.frontline.api.redis;

public enum RedisState {
    DISABLED,
    CREATED,
    CONNECTING,
    HEALTHY,
    DEGRADED,
    UNAVAILABLE,
    STOPPING,
    STOPPED,
    FAILED
}
