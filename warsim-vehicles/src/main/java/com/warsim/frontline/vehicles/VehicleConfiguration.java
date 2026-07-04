package com.warsim.frontline.vehicles;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public record VehicleConfiguration(
    boolean enabled,
    boolean modelEngineRequired,
    boolean modelEngineFailClosedWhenMissing,
    int maximumActiveVehicles,
    int tickIntervalTicks,
    boolean despawnOnMatchEnding,
    boolean despawnOnResetting,
    boolean despawnOnFailed,
    boolean allowAdminSpawnOutsidePlaying,
    VehicleMovementConfiguration defaultMovement,
    List<VehicleDefinition> definitions
) {
    public VehicleConfiguration {
        Objects.requireNonNull(defaultMovement, "defaultMovement");
        Objects.requireNonNull(definitions, "definitions");
        if (maximumActiveVehicles < 0 || maximumActiveVehicles > 128) {
            throw new IllegalArgumentException("maximum active vehicles must be 0-128");
        }
        if (tickIntervalTicks < 1 || tickIntervalTicks > 20) {
            throw new IllegalArgumentException("vehicle tick interval must be 1-20 ticks");
        }
        Set<VehicleId> ids = definitions.stream().map(VehicleDefinition::id).collect(Collectors.toSet());
        if (ids.size() != definitions.size()) {
            throw new IllegalArgumentException("Duplicate vehicle definition id");
        }
        definitions = List.copyOf(definitions);
    }

    public static VehicleConfiguration disabled() {
        return new VehicleConfiguration(
            false, false, false, 0, 5, true, true, true, false,
            VehicleMovementConfiguration.defaults(), List.of()
        );
    }
}
