package com.warsim.frontline.api.weapon;

import java.util.UUID;

public record HitResult(
    UUID targetUuid,
    HitZone hitZone,
    double distance,
    HitTargetType targetType
) {
    public HitResult(UUID targetUuid, HitZone hitZone, double distance) {
        this(targetUuid, hitZone, distance, HitTargetType.PLAYER);
    }

    public HitResult {
        targetType = targetType == null ? HitTargetType.PLAYER : targetType;
    }

    public boolean hit() {
        return targetUuid != null;
    }

    public static HitResult miss() {
        return new HitResult(null, null, Double.POSITIVE_INFINITY, HitTargetType.PLAYER);
    }
}
