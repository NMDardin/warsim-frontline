package com.warsim.frontline.api.performance;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Export-ready performance report. */
public record PerformanceReport(
    int schemaVersion,
    Instant generatedAt,
    String javaVersion,
    String paperVersion,
    String warSimVersion,
    String weaponsVersion,
    String craftEngineVersion,
    PerformanceSnapshot snapshot,
    List<String> knownEnvironmentLimitations,
    Map<String, String> sanitizedEnvironment
) {
    public PerformanceReport {
        Objects.requireNonNull(generatedAt, "generatedAt");
        Objects.requireNonNull(snapshot, "snapshot");
        knownEnvironmentLimitations = List.copyOf(Objects.requireNonNull(knownEnvironmentLimitations, "knownEnvironmentLimitations"));
        sanitizedEnvironment = Map.copyOf(Objects.requireNonNull(sanitizedEnvironment, "sanitizedEnvironment"));
        if (schemaVersion < 1) throw new IllegalArgumentException("Invalid report schema version");
    }
}
