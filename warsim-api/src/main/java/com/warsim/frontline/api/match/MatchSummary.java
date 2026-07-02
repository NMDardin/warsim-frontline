package com.warsim.frontline.api.match;

import java.time.Instant;
import java.util.UUID;

public record MatchSummary(
    UUID matchId,
    Instant createdAt,
    Instant endedAt,
    MatchEndReason endReason,
    int peakParticipants,
    boolean failed
) {
}
