package com.warsim.frontline.api.match;

import java.time.Instant;
import java.util.UUID;

public record MatchResetContext(
    UUID matchId,
    long lifecycleRevision,
    String nodeId,
    Instant requestedAt
) {
}
