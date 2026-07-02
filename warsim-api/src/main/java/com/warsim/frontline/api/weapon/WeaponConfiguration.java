package com.warsim.frontline.api.weapon;

import java.util.List;
import java.util.Objects;

public record WeaponConfiguration(
    boolean enabled,
    boolean friendlyFire,
    boolean allowSelfDamage,
    int maximumCandidatesPerShot,
    double globalMaximumRange,
    long maximumDeltaMillis,
    double headHeightRatio,
    double epsilon,
    int reloadCheckIntervalTicks,
    List<WeaponDefinition> definitions
) {
    public WeaponConfiguration {
        definitions = List.copyOf(Objects.requireNonNull(definitions, "definitions"));
        if (maximumCandidatesPerShot < 1 || maximumCandidatesPerShot > 100
            || !Double.isFinite(globalMaximumRange) || globalMaximumRange < 1
            || globalMaximumRange > 200 || maximumDeltaMillis < 1
            || maximumDeltaMillis > 1000 || !Double.isFinite(headHeightRatio)
            || headHeightRatio <= 0 || headHeightRatio >= 1
            || !Double.isFinite(epsilon) || epsilon <= 0 || epsilon > .01
            || reloadCheckIntervalTicks < 1 || reloadCheckIntervalTicks > 20) {
            throw new IllegalArgumentException("Invalid global weapon configuration");
        }
        var ids = new java.util.HashSet<WeaponId>();
        var items = new java.util.HashSet<String>();
        for (WeaponDefinition definition : definitions) {
            if (definition.maximumRange() > globalMaximumRange
                || definition.fireMode() != FireMode.SEMI_AUTO
                || !ids.add(definition.weaponId())
                || !items.add(definition.craftEngineItemId())) {
                throw new IllegalArgumentException("Invalid or duplicate weapon definition");
            }
        }
    }

    public static WeaponConfiguration disabled() {
        return new WeaponConfiguration(
            false, false, false, 100, 200, 250, .25, 1.0E-6, 2, List.of()
        );
    }
}
