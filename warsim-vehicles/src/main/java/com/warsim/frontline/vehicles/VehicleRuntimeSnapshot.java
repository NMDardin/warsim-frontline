package com.warsim.frontline.vehicles;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record VehicleRuntimeSnapshot(
    VehicleRuntimeId runtimeId,
    VehicleId vehicleId,
    String displayName,
    String worldName,
    double x,
    double y,
    double z,
    float yaw,
    float pitch,
    double speedBlocksPerSecond,
    Optional<UUID> driverUuid,
    VehicleRuntimeState state,
    VehicleHealthSnapshot health,
    String modelBindingStatus,
    Instant spawnedAt,
    Instant lastUpdatedAt
) {
    public VehicleRuntimeSnapshot {
        Objects.requireNonNull(runtimeId, "runtimeId");
        Objects.requireNonNull(vehicleId, "vehicleId");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(worldName, "worldName");
        driverUuid = driverUuid == null ? Optional.empty() : driverUuid;
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(health, "health");
        Objects.requireNonNull(modelBindingStatus, "modelBindingStatus");
        Objects.requireNonNull(spawnedAt, "spawnedAt");
        Objects.requireNonNull(lastUpdatedAt, "lastUpdatedAt");
    }
}
