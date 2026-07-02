package com.warsim.frontline.api.match;

import java.util.EnumSet;

public enum MatchState {
    BOOTSTRAPPING,
    WAITING,
    WARMUP,
    PLAYING,
    ENDING,
    RESETTING,
    STOPPING,
    STOPPED,
    FAILED;

    public boolean canTransitionTo(MatchState target) {
        return switch (this) {
            case BOOTSTRAPPING -> EnumSet.of(WAITING, FAILED, STOPPING).contains(target);
            case WAITING -> EnumSet.of(WARMUP, RESETTING, FAILED, STOPPING).contains(target);
            case WARMUP -> EnumSet.of(WAITING, PLAYING, FAILED, STOPPING).contains(target);
            case PLAYING -> EnumSet.of(ENDING, FAILED, STOPPING).contains(target);
            case ENDING -> EnumSet.of(RESETTING, FAILED, STOPPING).contains(target);
            case RESETTING -> EnumSet.of(WAITING, FAILED, STOPPING).contains(target);
            case FAILED -> EnumSet.of(STOPPING, STOPPED).contains(target);
            case STOPPING -> target == STOPPED;
            case STOPPED -> false;
        };
    }
}
