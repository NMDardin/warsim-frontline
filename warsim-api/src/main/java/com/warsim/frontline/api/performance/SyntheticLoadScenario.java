package com.warsim.frontline.api.performance;

import java.util.List;
import java.util.Objects;

/** Immutable synthetic load scenario definition. */
public record SyntheticLoadScenario(
    String id,
    SyntheticLoadScenarioType type,
    String displayName,
    int warmupIterations,
    int measurementIterations,
    int maximumIterations,
    long maximumDurationMillis,
    String loadScenarioId,
    List<PerformanceComponent> components,
    long estimatedOperations
) {
    public SyntheticLoadScenario {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(displayName, "displayName");
        components = List.copyOf(Objects.requireNonNull(components, "components"));
        if (!id.matches("[A-Z0-9_]{3,64}") || displayName.isBlank()
            || warmupIterations < 0 || measurementIterations < 1
            || maximumIterations < 1 || measurementIterations > maximumIterations
            || maximumDurationMillis < 100 || maximumDurationMillis > 3_600_000
            || estimatedOperations < 0) {
            throw new IllegalArgumentException("Invalid synthetic load scenario");
        }
    }
}
