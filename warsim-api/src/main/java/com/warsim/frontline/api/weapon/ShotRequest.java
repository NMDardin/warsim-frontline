package com.warsim.frontline.api.weapon;

import java.util.Objects;
import java.util.UUID;

public record ShotRequest(
    ShotId shotId,
    UUID matchId,
    long lifecycleRevision,
    UUID shooterUuid,
    WeaponId weaponId,
    String worldName,
    Vector3 origin,
    Vector3 normalizedDirection,
    long monotonicNanos,
    long deterministicSeed
) {
    public ShotRequest {
        Objects.requireNonNull(shotId, "shotId");
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(shooterUuid, "shooterUuid");
        Objects.requireNonNull(weaponId, "weaponId");
        Objects.requireNonNull(worldName, "worldName");
        Objects.requireNonNull(origin, "origin");
        normalizedDirection = Objects.requireNonNull(
            normalizedDirection, "normalizedDirection"
        ).normalized();
    }
}
