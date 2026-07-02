package com.warsim.frontline.api.weapon;

import java.util.Objects;

public record WeaponDefinition(
    WeaponId weaponId,
    String displayName,
    WeaponCategory category,
    FireMode fireMode,
    String craftEngineItemId,
    AmmoConfiguration ammo,
    int roundsPerMinute,
    double maximumRange,
    AccuracyConfiguration accuracy,
    DamageConfiguration damage
) {
    public WeaponDefinition {
        Objects.requireNonNull(weaponId, "weaponId");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(fireMode, "fireMode");
        Objects.requireNonNull(ammo, "ammo");
        Objects.requireNonNull(accuracy, "accuracy");
        Objects.requireNonNull(damage, "damage");
        if (displayName == null || displayName.isBlank() || displayName.length() > 48) {
            throw new IllegalArgumentException("Invalid display name");
        }
        if (craftEngineItemId == null
            || !craftEngineItemId.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) {
            throw new IllegalArgumentException("Invalid CraftEngine item ID");
        }
        if (roundsPerMinute < 1 || roundsPerMinute > 1200
            || !Double.isFinite(maximumRange) || maximumRange < 1 || maximumRange > 200) {
            throw new IllegalArgumentException("Invalid firing configuration");
        }
        if (damage.points().getLast().distance() < maximumRange) {
            throw new IllegalArgumentException("Last damage point must cover maximum range");
        }
    }

    public long shotIntervalNanos() {
        return Math.ceilDiv(60_000_000_000L, roundsPerMinute);
    }
}
