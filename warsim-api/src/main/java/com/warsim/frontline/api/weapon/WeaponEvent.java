package com.warsim.frontline.api.weapon;

import java.time.Instant;
import java.util.UUID;

public interface WeaponEvent {
    UUID matchId();
    ShotId shotId();
    WeaponId weaponId();
    UUID playerUuid();
    Instant occurredAt();
}
