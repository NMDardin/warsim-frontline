package com.warsim.frontline.api.performance;

import java.util.List;
import java.util.Objects;

/** Execution plan preview that does not run synthetic work. */
public record SyntheticDryRunPlan(
    SyntheticLoadScenario scenario,
    boolean executable,
    String message,
    int warmupIterations,
    int measurementIterations,
    long estimatedOperations,
    List<PerformanceComponent> components
) {
    public SyntheticDryRunPlan {
        Objects.requireNonNull(scenario, "scenario");
        Objects.requireNonNull(message, "message");
        components = List.copyOf(Objects.requireNonNull(components, "components"));
    }
}
