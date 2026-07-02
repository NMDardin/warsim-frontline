package com.warsim.frontline.api.combat;

import com.warsim.frontline.api.weapon.WeaponId;
import java.util.UUID;

public record DamageCorrelationToken(
    UUID correlationId,
    UUID attackerUuid,
    long attackerLifeRevision,
    UUID targetUuid,
    long targetLifeRevision,
    UUID matchId,
    long lifecycleRevision,
    WeaponId weaponId,
    boolean headshot,
    double distance,
    boolean friendly,
    long createdAtMonotonic,
    long expiresAtMonotonic
) {
    public DamageCorrelationToken {
        if (correlationId == null || attackerUuid == null || targetUuid == null
            || matchId == null || weaponId == null) {
            throw new IllegalArgumentException("Damage correlation identity is required");
        }
        if (attackerLifeRevision < 0 || targetLifeRevision < 0 || lifecycleRevision < 0) {
            throw new IllegalArgumentException("Revisions must be non-negative");
        }
        if (!Double.isFinite(distance) || distance < 0) {
            throw new IllegalArgumentException("distance must be finite and non-negative");
        }
        if (expiresAtMonotonic <= createdAtMonotonic) {
            throw new IllegalArgumentException("expiresAtMonotonic must be after createdAtMonotonic");
        }
    }
}
