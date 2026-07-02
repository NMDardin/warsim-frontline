package com.warsim.frontline.api.weapon;

public record ShotResult(
    ShotRequest request,
    ShotOutcome outcome,
    WeaponFailureReason failureReason,
    HitResult hit,
    double requestedDamage,
    WeaponRuntimeState state
) {
    public boolean fired() {
        return outcome.name().startsWith("FIRED_") || outcome == ShotOutcome.FRIENDLY_BLOCKED;
    }
}
