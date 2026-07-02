package com.warsim.frontline.api.match;

import java.time.Instant;

public record MatchMetricsSnapshot(
    MatchState currentState,
    long lifecycleRevision,
    long createdMatches,
    long completedMatches,
    long failedMatches,
    long stateTransitions,
    long invalidTransitions,
    int activeParticipants,
    int peakParticipants,
    long warmupCancellations,
    long administratorStarts,
    long administratorEnds,
    long resets,
    long resetFailures,
    long staleTasksRejected,
    long eventListenerFailures,
    Instant lastTransitionAt,
    Instant lastMatchStartedAt,
    Instant lastMatchEndedAt
) {
}
