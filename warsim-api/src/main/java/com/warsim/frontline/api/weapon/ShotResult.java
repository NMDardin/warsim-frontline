package com.warsim.frontline.api.weapon;

import com.warsim.frontline.api.roster.CombatRelation;

public record ShotResult(
    ShotRequest request,
    ShotOutcome outcome,
    WeaponFailureReason failureReason,
    HitResult hit,
    double requestedDamage,
    WeaponRuntimeState state,
    CombatRelation relation,
    boolean friendly
) {
    public ShotResult(
        ShotRequest request,
        ShotOutcome outcome,
        WeaponFailureReason failureReason,
        HitResult hit,
        double requestedDamage,
        WeaponRuntimeState state
    ) {
        this(request, outcome, failureReason, hit, requestedDamage, state, CombatRelation.UNKNOWN, false);
    }

    public ShotResult {
        relation = relation == null ? CombatRelation.UNKNOWN : relation;
    }

    public boolean fired() {
        return outcome.name().startsWith("FIRED_") || outcome == ShotOutcome.FRIENDLY_BLOCKED;
    }
}
