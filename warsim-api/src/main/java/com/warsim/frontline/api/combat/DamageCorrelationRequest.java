package com.warsim.frontline.api.combat;

import com.warsim.frontline.api.weapon.WeaponId;
import java.util.Optional;
import java.util.UUID;

public record DamageCorrelationRequest(
    UUID attackerUuid,
    long attackerLifeRevision,
    UUID targetUuid,
    long targetLifeRevision,
    UUID matchId,
    long lifecycleRevision,
    Optional<WeaponId> weaponId,
    boolean headshot,
    double distance,
    boolean friendly,
    long createdAtMonotonic,
    long ttlNanos
) {
    public DamageCorrelationRequest {
        if (attackerUuid == null || targetUuid == null || matchId == null) {
            throw new IllegalArgumentException("Damage correlation request identity is required");
        }
        weaponId = weaponId == null ? Optional.empty() : weaponId;
        if (ttlNanos <= 0) throw new IllegalArgumentException("ttlNanos must be positive");
    }
}
