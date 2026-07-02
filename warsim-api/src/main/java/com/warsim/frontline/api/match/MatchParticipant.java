package com.warsim.frontline.api.match;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record MatchParticipant(
    UUID playerUuid,
    String currentName,
    UUID matchId,
    Instant joinedAt,
    MatchParticipantState state,
    boolean joinedDuringRound
) {
    public MatchParticipant {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(joinedAt, "joinedAt");
        Objects.requireNonNull(state, "state");
        if (currentName == null || !currentName.matches("[A-Za-z0-9_]{1,16}")) {
            throw new IllegalArgumentException("Invalid player name");
        }
    }
}
