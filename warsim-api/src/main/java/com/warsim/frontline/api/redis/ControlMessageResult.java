package com.warsim.frontline.api.redis;

public enum ControlMessageResult {
    ACKNOWLEDGED,
    RETRY,
    DEAD_LETTER,
    DUPLICATE,
    EXPIRED,
    INVALID
}
