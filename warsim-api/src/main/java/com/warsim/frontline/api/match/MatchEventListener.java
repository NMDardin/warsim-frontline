package com.warsim.frontline.api.match;

@FunctionalInterface
public interface MatchEventListener {
    void onEvent(MatchEvent event);
}
