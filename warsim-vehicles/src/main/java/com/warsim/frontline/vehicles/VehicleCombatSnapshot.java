package com.warsim.frontline.vehicles;

import java.util.Objects;

public record VehicleCombatSnapshot(
    boolean combatEnabled,
    boolean allowAdminDamage,
    boolean cancelVanillaAnchorDamage,
    int activeVehicles,
    int destroyedVehicles,
    int scheduledDespawns,
    String lastDamageSummary
) {
    public VehicleCombatSnapshot {
        if (activeVehicles < 0 || destroyedVehicles < 0 || scheduledDespawns < 0) {
            throw new IllegalArgumentException("Vehicle combat counts must be non-negative");
        }
        lastDamageSummary = Objects.requireNonNullElse(lastDamageSummary, "none");
    }
}
