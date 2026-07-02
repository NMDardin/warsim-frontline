package com.warsim.frontline.api.combat;

import java.util.UUID;

public record SpawnProtectionSnapshot(
    UUID playerUuid,
    UUID matchId,
    long lifecycleRevision,
    long lifeRevision,
    long deploymentRevision,
    String spawnId,
    long startedAtMonotonic,
    long expiresAtMonotonic,
    SpawnPositionSnapshot spawnPosition
) {}
