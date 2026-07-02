package com.warsim.frontline.api.weapon;

import java.time.Instant;
import java.util.UUID;

public record WeaponFiredEvent(
    UUID matchId, ShotId shotId, WeaponId weaponId, UUID playerUuid,
    ShotOutcome outcome, Instant occurredAt
) implements WeaponEvent {
}
