package com.warsim.frontline.api.performance;

import java.time.Instant;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;

/** Snapshot of one metric's bounded sample window and lifecycle counters. */
public record PerformanceMetricSnapshot(
    PerformanceMetricId metricId,
    PerformanceComponent component,
    UUID matchId,
    long lifecycleRevision,
    long sampleCount,
    long successCount,
    long failureCount,
    OptionalLong lastNanos,
    OptionalLong minimumNanos,
    OptionalLong maximumNanos,
    OptionalLong meanNanos,
    PerformancePercentiles percentiles,
    double samplesPerSecond,
    Instant lastSampleAt,
    Instant lastSlowSampleAt
) {
    public PerformanceMetricSnapshot {
        Objects.requireNonNull(metricId, "metricId");
        Objects.requireNonNull(component, "component");
        Objects.requireNonNull(percentiles, "percentiles");
        if (sampleCount < 0 || successCount < 0 || failureCount < 0 || samplesPerSecond < 0) {
            throw new IllegalArgumentException("Invalid performance metric counters");
        }
    }
}
