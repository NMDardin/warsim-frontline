package com.warsim.frontline.api.loadtest;

import java.util.Objects;

/** Fixed line or blocked lane for weapon load scenarios. */
public record LoadLaneDefinition(
    String laneId,
    LoadCoordinate start,
    LoadCoordinate end,
    LoadCoordinate blocker,
    double expectedDistance,
    String description
) {
    public LoadLaneDefinition {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!laneId.matches("[a-z0-9_-]{1,48}") || !start.world().equals(end.world())
            || expectedDistance < 0 || !Double.isFinite(expectedDistance)) {
            throw new IllegalArgumentException("Invalid load lane definition");
        }
    }
}
