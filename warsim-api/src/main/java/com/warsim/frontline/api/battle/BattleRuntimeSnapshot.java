package com.warsim.frontline.api.battle;

import com.warsim.frontline.api.match.MatchState;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable view of the local battle runtime.
 */
public record BattleRuntimeSnapshot(
    boolean available,
    UUID matchId,
    long lifecycleRevision,
    MatchState matchState
) {
    public BattleRuntimeSnapshot {
        Objects.requireNonNull(matchState, "matchState");
        if (available) {
            Objects.requireNonNull(matchId, "matchId");
            if (lifecycleRevision < 0) {
                throw new IllegalArgumentException("lifecycleRevision must be non-negative");
            }
        }
    }

    public static BattleRuntimeSnapshot unavailable() {
        return new BattleRuntimeSnapshot(false, null, 0, MatchState.STOPPED);
    }
}
