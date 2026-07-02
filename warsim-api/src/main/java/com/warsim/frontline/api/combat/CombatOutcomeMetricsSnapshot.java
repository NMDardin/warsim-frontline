package com.warsim.frontline.api.combat;

public record CombatOutcomeMetricsSnapshot(
    long damageContributionsRecorded,
    long contributionEvictions,
    long killsRecorded,
    long deathsRecorded,
    long assistsRecorded,
    long headshotKills,
    long teamKills,
    long suicides,
    long environmentalDeaths,
    long duplicateDeathsRejected,
    long staleLifeEventsRejected,
    long spawnProtectionsCreated,
    long spawnProtectionsExpired,
    long spawnProtectionsRemovedByAttack,
    long spawnProtectionsRemovedByMovement,
    long spawnProtectionsRemovedByObjective,
    long protectedDamageCancelled,
    long hudUpdates,
    long hudUpdatesDeduplicated,
    long feedbackMessagesSubmitted,
    long feedbackMessagesSuppressed,
    long killFeedEntriesCreated,
    long combatStateCleanupCount,
    long listenerFailures
) {}
