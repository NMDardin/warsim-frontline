package com.warsim.frontline.api.redis;

public enum NodeAvailability {
    STARTING,
    AVAILABLE,
    FULL,
    DRAINING,
    STOPPING,
    UNAVAILABLE
}
