package com.warsim.frontline.api.weapon;

import java.util.Objects;
import java.util.UUID;

public record WeaponRuntimeState(
    UUID playerUuid,
    UUID matchId,
    WeaponId weaponId,
    int magazineAmmo,
    int reserveAmmo,
    ReloadState reloadState,
    long reloadStartedAtNanos,
    long reloadCompletesAtNanos,
    long nextAllowedShotAtNanos,
    long shotsFired,
    long revision
) {
    public WeaponRuntimeState {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(weaponId, "weaponId");
        Objects.requireNonNull(reloadState, "reloadState");
        if (magazineAmmo < 0 || reserveAmmo < 0 || shotsFired < 0 || revision < 0) {
            throw new IllegalArgumentException("Invalid runtime state");
        }
    }
}
