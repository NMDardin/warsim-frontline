package com.warsim.frontline.match;

import com.warsim.frontline.api.ModuleState;
import com.warsim.frontline.api.match.MatchState;
import com.warsim.frontline.api.redis.NodeAvailability;

public final class MatchNodeStatusMapper {
    private MatchNodeStatusMapper() {
    }

    public static Publication map(MatchState state, boolean allowMidRoundJoin) {
        return switch (state) {
            case BOOTSTRAPPING -> new Publication(
                ModuleState.STARTING, NodeAvailability.STARTING, false
            );
            case WAITING, WARMUP -> new Publication(
                ModuleState.RUNNING, NodeAvailability.AVAILABLE, true
            );
            case PLAYING -> allowMidRoundJoin
                ? new Publication(ModuleState.RUNNING, NodeAvailability.AVAILABLE, true)
                : new Publication(ModuleState.RUNNING, NodeAvailability.DRAINING, false);
            case ENDING, RESETTING -> new Publication(
                ModuleState.RUNNING, NodeAvailability.DRAINING, false
            );
            case STOPPING -> new Publication(
                ModuleState.STOPPING, NodeAvailability.STOPPING, false
            );
            case STOPPED -> new Publication(
                ModuleState.STOPPED, NodeAvailability.UNAVAILABLE, false
            );
            case FAILED -> new Publication(
                ModuleState.FAILED, NodeAvailability.UNAVAILABLE, false
            );
        };
    }

    public record Publication(
        ModuleState lifecycleState,
        NodeAvailability availability,
        boolean acceptingPlayers
    ) {
    }
}
