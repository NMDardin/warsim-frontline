package com.warsim.frontline.network.redis;

import com.warsim.frontline.api.redis.ControlMessageResult;

public final class ControlDeliveryPolicy {
    private final int maximumAttempts;

    public ControlDeliveryPolicy(int maximumAttempts) {
        if (maximumAttempts < 1 || maximumAttempts > 10) {
            throw new IllegalArgumentException("maximumAttempts must be 1-10");
        }
        this.maximumAttempts = maximumAttempts;
    }

    public Action decide(ControlMessageResult result, int attempt) {
        if (result == ControlMessageResult.ACKNOWLEDGED
            || result == ControlMessageResult.DUPLICATE
            || result == ControlMessageResult.EXPIRED
            || result == ControlMessageResult.INVALID) {
            return Action.ACKNOWLEDGE;
        }
        if (result == ControlMessageResult.RETRY && attempt < maximumAttempts) {
            return Action.RETRY;
        }
        return Action.DEAD_LETTER;
    }

    public enum Action {
        ACKNOWLEDGE,
        RETRY,
        DEAD_LETTER
    }
}
