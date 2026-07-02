package com.warsim.frontline.api.match;

import java.time.Instant;
import java.util.UUID;

public record MatchSnapshot(
    UUID matchId,
    String nodeId,
    String modeId,
    MatchState state,
    long lifecycleRevision,
    Instant createdAt,
    Instant stateEnteredAt,
    Instant scheduledStartAt,
    Instant roundStartedAt,
    Instant scheduledEndAt,
    MatchEndReason endReason,
    String endSummary,
    String lastErrorSummary,
    int currentPlayers,
    int maximumPlayers,
    int minimumPlayers,
    boolean acceptingPlayers,
    boolean manualWaiting
) {
}
