package com.warsim.frontline.api.performance;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;

/** Result of a completed, failed, or cancelled synthetic load run. */
public record SyntheticLoadResult(
    UUID runId,
    String scenarioId,
    SyntheticLoadScenarioType scenarioType,
    boolean completed,
    boolean cancelled,
    String failureReason,
    int warmupIterations,
    int measurementIterations,
    int completedMeasurements,
    long totalNanos,
    OptionalLong meanNanos,
    PerformancePercentiles percentiles,
    OptionalLong maximumNanos,
    double samplesPerSecond,
    Instant startedAt,
    Instant completedAt,
    Map<String, String> deterministicInputs
) {
    public SyntheticLoadResult {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(scenarioId, "scenarioId");
        Objects.requireNonNull(scenarioType, "scenarioType");
        Objects.requireNonNull(percentiles, "percentiles");
        Objects.requireNonNull(startedAt, "startedAt");
        deterministicInputs = Map.copyOf(Objects.requireNonNull(deterministicInputs, "deterministicInputs"));
        if (warmupIterations < 0 || measurementIterations < 0 || completedMeasurements < 0 || totalNanos < 0) {
            throw new IllegalArgumentException("Invalid synthetic result");
        }
    }
}
