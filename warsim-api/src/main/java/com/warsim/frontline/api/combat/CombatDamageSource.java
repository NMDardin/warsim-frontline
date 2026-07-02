package com.warsim.frontline.api.combat;

import com.warsim.frontline.api.weapon.WeaponId;
import java.util.Optional;
import java.util.UUID;

public record CombatDamageSource(
    UUID attackerUuid,
    long attackerLifeRevision,
    CombatDamageType damageType,
    Optional<WeaponId> weaponId,
    boolean headshot,
    boolean friendly,
    double distance
) {
    public CombatDamageSource {
        if (attackerUuid == null) throw new IllegalArgumentException("attackerUuid");
        if (damageType == null) throw new IllegalArgumentException("damageType");
        weaponId = weaponId == null ? Optional.empty() : weaponId;
        if (!Double.isFinite(distance) || distance < 0) distance = 0;
    }
}
