package com.warsim.frontline.api.weapon;

import java.time.Instant;
import java.util.UUID;

public record ShotKillEvent(
    UUID matchId, ShotId shotId, WeaponId weaponId, UUID playerUuid,
    UUID targetUuid, Instant occurredAt
) implements WeaponEvent {
}
