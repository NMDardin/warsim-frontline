package com.warsim.frontline.api.loadtest;

import java.util.List;
import java.util.Objects;

/** Axis-aligned configured load-test zone. */
public record LoadZoneDefinition(
    String zoneId,
    String world,
    LoadCoordinate center,
    double minimumX,
    double minimumY,
    double minimumZ,
    double maximumX,
    double maximumY,
    double maximumZ,
    List<String> purposes,
    int maximumSuggestedParticipants,
    String scenarioId,
    String version,
    String description
) {
    public LoadZoneDefinition {
        Objects.requireNonNull(center, "center");
        purposes = List.copyOf(Objects.requireNonNull(purposes, "purposes"));
        if (!zoneId.matches("[a-z0-9_-]{1,48}") || world == null || world.isBlank()
            || minimumX > maximumX || minimumY > maximumY || minimumZ > maximumZ
            || maximumSuggestedParticipants < 0 || maximumSuggestedParticipants > 100
            || scenarioId == null || scenarioId.isBlank() || version == null || version.isBlank()) {
            throw new IllegalArgumentException("Invalid load zone definition");
        }
    }

    public boolean contains(LoadCoordinate coordinate) {
        return coordinate.world().equals(world)
            && coordinate.x() >= minimumX && coordinate.x() <= maximumX
            && coordinate.y() >= minimumY && coordinate.y() <= maximumY
            && coordinate.z() >= minimumZ && coordinate.z() <= maximumZ;
    }
}
