package com.warsim.frontline.api.weapon;

import com.warsim.frontline.api.roster.CombatRelation;

public record DamageRequest(
    WeaponDefinition definition,
    double distance,
    HitZone hitZone,
    CombatRelation relation,
    boolean friendlyFire,
    boolean allowSelfDamage
) {
}
