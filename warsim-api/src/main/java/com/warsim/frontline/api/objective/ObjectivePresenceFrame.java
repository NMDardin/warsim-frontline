package com.warsim.frontline.api.objective;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ObjectivePresenceFrame(
    UUID matchId,
    long lifecycleRevision,
    long monotonicNanos,
    Instant sampledAt,
    List<ObjectivePlayerPresence> players
) {
    public ObjectivePresenceFrame {
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(sampledAt, "sampledAt");
        players = List.copyOf(players);
    }
}
