package com.warsim.frontline.api.weapon;

import java.time.Instant;

public record WeaponMetricsSnapshot(
    int configuredWeapons, int activeWeaponStates,
    long shotsRequested, long shotsFired, long shotsRejected,
    long cooldownRejections, long emptyRejections,
    long reloadsStarted, long reloadsCompleted, long reloadsCancelled,
    long candidatesSampled, long candidateLimitTruncations, long rayTests,
    long blockObstructions, long misses, long bodyHits, long headHits,
    long friendlyHitsBlocked, long damageApplications, long kills,
    long staleShotsRejected, long invalidItemsRejected, long listenerFailures,
    long lastShotProcessingNanos, long maximumShotProcessingNanos,
    Instant lastShotAt
) {
}
