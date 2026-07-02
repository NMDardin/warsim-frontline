package com.warsim.frontline.api.weapon;

import java.time.Instant;
import java.util.UUID;

public record ShotBlockedEvent(
    UUID matchId, ShotId shotId, WeaponId weaponId, UUID playerUuid,
    Instant occurredAt
) implements WeaponEvent {
}
