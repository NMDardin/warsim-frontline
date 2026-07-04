package com.warsim.frontline.vehicles;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public record VehicleDefinition(
    VehicleId id,
    String displayName,
    String modelEngineModelId,
    String anchorEntityType,
    List<VehicleSeatDefinition> seats,
    VehicleMovementConfiguration movement
) {
    public VehicleDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(modelEngineModelId, "modelEngineModelId");
        Objects.requireNonNull(anchorEntityType, "anchorEntityType");
        Objects.requireNonNull(seats, "seats");
        Objects.requireNonNull(movement, "movement");
        if (displayName.isBlank() || displayName.length() > 80) {
            throw new IllegalArgumentException("Invalid vehicle display name");
        }
        if (modelEngineModelId.isBlank() || modelEngineModelId.length() > 96) {
            throw new IllegalArgumentException("Invalid ModelEngine model id");
        }
        if (anchorEntityType.isBlank() || anchorEntityType.length() > 64) {
            throw new IllegalArgumentException("Invalid vehicle anchor entity type");
        }
        if (seats.isEmpty() || seats.size() > 8) {
            throw new IllegalArgumentException("Vehicle seats must contain 1-8 entries");
        }
        Set<String> ids = seats.stream().map(VehicleSeatDefinition::id).collect(Collectors.toSet());
        if (ids.size() != seats.size()) {
            throw new IllegalArgumentException("Vehicle seat ids must be unique");
        }
        if (seats.stream().filter(VehicleSeatDefinition::driver).count() != 1) {
            throw new IllegalArgumentException("Vehicle must define exactly one driver seat");
        }
        seats = List.copyOf(seats);
    }
}
