package com.warsim.frontline.api.weapon;

import java.util.UUID;

public record HitResult(UUID targetUuid, HitZone hitZone, double distance) {
    public boolean hit() {
        return targetUuid != null;
    }

    public static HitResult miss() {
        return new HitResult(null, null, Double.POSITIVE_INFINITY);
    }
}
