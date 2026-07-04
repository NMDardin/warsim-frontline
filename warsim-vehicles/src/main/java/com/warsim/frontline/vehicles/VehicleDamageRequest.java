package com.warsim.frontline.vehicles;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record VehicleDamageRequest(
    VehicleRuntimeId runtimeId,
    Optional<VehicleId> vehicleId,
    double amount,
    VehicleDamageType damageType,
    Optional<UUID> attackerUuid,
    String sourceDescription,
    Instant occurredAt
) {
    public VehicleDamageRequest {
        Objects.requireNonNull(runtimeId, "runtimeId");
        vehicleId = vehicleId == null ? Optional.empty() : vehicleId;
        if (!Double.isFinite(amount)) {
            throw new IllegalArgumentException("Vehicle damage amount must be finite");
        }
        damageType = damageType == null ? VehicleDamageType.UNKNOWN : damageType;
        attackerUuid = attackerUuid == null ? Optional.empty() : attackerUuid;
        sourceDescription = sourceDescription == null ? "" : sourceDescription;
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }
}
