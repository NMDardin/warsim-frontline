package com.warsim.frontline.vehicles;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record VehicleHealthSnapshot(
    double currentHealth,
    double maxHealth,
    boolean destroyed,
    Optional<VehicleDamageType> lastDamageType,
    Optional<UUID> lastAttackerUuid,
    double lastDamageAmount,
    Optional<Instant> lastDamageAt,
    boolean scheduledDespawn
) {
    public VehicleHealthSnapshot {
        if (!Double.isFinite(currentHealth) || !Double.isFinite(maxHealth)
            || !Double.isFinite(lastDamageAmount)) {
            throw new IllegalArgumentException("Vehicle health snapshot values must be finite");
        }
        lastDamageType = lastDamageType == null ? Optional.empty() : lastDamageType;
        lastAttackerUuid = lastAttackerUuid == null ? Optional.empty() : lastAttackerUuid;
        lastDamageAt = lastDamageAt == null ? Optional.empty() : lastDamageAt;
    }

    public static VehicleHealthSnapshot initial(VehicleHealthConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        return new VehicleHealthSnapshot(
            configuration.maxHealth(), configuration.maxHealth(), false,
            Optional.empty(), Optional.empty(), 0.0, Optional.empty(), false
        );
    }
}
