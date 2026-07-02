package com.warsim.frontline.match.redis;

import com.warsim.frontline.api.ModuleState;
import com.warsim.frontline.api.redis.NodeAvailability;

public record PaperNodePublication(
    ModuleState lifecycleState,
    NodeAvailability availability,
    boolean acceptingPlayers,
    int maximumPlayers
) {
}
