package com.warsim.frontline.weapons;

import com.warsim.frontline.api.weapon.WeaponMetricsSnapshot;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

final class MutableWeaponMetrics {
    final AtomicLong shotsRequested = new AtomicLong();
    final AtomicLong shotsFired = new AtomicLong();
    final AtomicLong shotsRejected = new AtomicLong();
    final AtomicLong cooldownRejections = new AtomicLong();
    final AtomicLong emptyRejections = new AtomicLong();
    final AtomicLong reloadsStarted = new AtomicLong();
    final AtomicLong reloadsCompleted = new AtomicLong();
    final AtomicLong reloadsCancelled = new AtomicLong();
    final AtomicLong candidatesSampled = new AtomicLong();
    final AtomicLong candidateLimitTruncations = new AtomicLong();
    final AtomicLong rayTests = new AtomicLong();
    final AtomicLong blockObstructions = new AtomicLong();
    final AtomicLong misses = new AtomicLong();
    final AtomicLong bodyHits = new AtomicLong();
    final AtomicLong headHits = new AtomicLong();
    final AtomicLong friendlyHitsBlocked = new AtomicLong();
    final AtomicLong damageApplications = new AtomicLong();
    final AtomicLong kills = new AtomicLong();
    final AtomicLong staleShotsRejected = new AtomicLong();
    final AtomicLong invalidItemsRejected = new AtomicLong();
    final AtomicLong listenerFailures = new AtomicLong();
    final AtomicLong lastShotProcessingNanos = new AtomicLong();
    final AtomicLong maximumShotProcessingNanos = new AtomicLong();
    volatile Instant lastShotAt;

    WeaponMetricsSnapshot snapshot(int configured, int active) {
        return new WeaponMetricsSnapshot(
            configured, active, shotsRequested.get(), shotsFired.get(), shotsRejected.get(),
            cooldownRejections.get(), emptyRejections.get(), reloadsStarted.get(),
            reloadsCompleted.get(), reloadsCancelled.get(), candidatesSampled.get(),
            candidateLimitTruncations.get(), rayTests.get(), blockObstructions.get(),
            misses.get(), bodyHits.get(), headHits.get(), friendlyHitsBlocked.get(),
            damageApplications.get(), kills.get(), staleShotsRejected.get(),
            invalidItemsRejected.get(), listenerFailures.get(),
            lastShotProcessingNanos.get(), maximumShotProcessingNanos.get(), lastShotAt
        );
    }
}
