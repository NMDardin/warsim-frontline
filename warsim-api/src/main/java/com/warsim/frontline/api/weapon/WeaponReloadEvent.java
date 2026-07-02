package com.warsim.frontline.api.weapon;

import java.time.Instant;
import java.util.UUID;

public record WeaponReloadEvent(
    UUID matchId, ShotId shotId, WeaponId weaponId, UUID playerUuid,
    ReloadState reloadState, Instant occurredAt
) implements WeaponEvent {
}
