package com.warsim.frontline.api.weapon;

import java.time.Instant;
import java.util.UUID;

public record ShotHitEvent(
    UUID matchId, ShotId shotId, WeaponId weaponId, UUID playerUuid,
    UUID targetUuid, HitZone hitZone, double distance, Instant occurredAt
) implements WeaponEvent {
}
