package com.warsim.frontline.api.weapon;

import java.time.Instant;
import java.util.UUID;

public record ShotDamageAppliedEvent(
    UUID matchId, ShotId shotId, WeaponId weaponId, UUID playerUuid,
    UUID targetUuid, double requestedDamage, Instant occurredAt
) implements WeaponEvent {
}
