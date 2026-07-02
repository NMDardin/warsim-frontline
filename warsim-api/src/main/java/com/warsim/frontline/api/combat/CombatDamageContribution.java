package com.warsim.frontline.api.combat;

import com.warsim.frontline.api.weapon.WeaponId;
import java.util.Optional;
import java.util.UUID;

public record CombatDamageContribution(
    UUID attackerUuid,
    long attackerLifeRevision,
    UUID targetUuid,
    long targetLifeRevision,
    UUID matchId,
    Optional<WeaponId> weaponId,
    CombatDamageType damageType,
    double accumulatedDamage,
    long lastDamageAtMonotonic,
    boolean headshot,
    boolean friendly,
    long expiryAtMonotonic
) {
    public CombatDamageContribution {
        if (attackerUuid == null || targetUuid == null || matchId == null || damageType == null) {
            throw new IllegalArgumentException("Contribution identity is required");
        }
        weaponId = weaponId == null ? Optional.empty() : weaponId;
        if (!Double.isFinite(accumulatedDamage) || accumulatedDamage < 0) {
            throw new IllegalArgumentException("accumulatedDamage must be finite and non-negative");
        }
    }
}
