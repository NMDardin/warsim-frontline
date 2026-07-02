package com.warsim.frontline.api.match;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record MatchTransition(
    UUID matchId,
    MatchState previousState,
    MatchState nextState,
    String transitionReason,
    Instant transitionedAt,
    long lifecycleRevision
) {
    public MatchTransition {
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(previousState, "previousState");
        Objects.requireNonNull(nextState, "nextState");
        Objects.requireNonNull(transitionReason, "transitionReason");
        Objects.requireNonNull(transitionedAt, "transitionedAt");
    }
}
