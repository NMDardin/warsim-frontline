package com.warsim.frontline.api.performance;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Bounded slow-operation alert. */
public record PerformanceAlert(
    PerformanceAlertSeverity severity,
    PerformanceMetricId metricId,
    PerformanceComponent component,
    UUID matchId,
    long lifecycleRevision,
    long durationNanos,
    long thresholdNanos,
    Instant occurredAt,
    Map<String, String> context
) {
    public PerformanceAlert {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(metricId, "metricId");
        Objects.requireNonNull(component, "component");
        Objects.requireNonNull(occurredAt, "occurredAt");
        context = Map.copyOf(Objects.requireNonNull(context, "context"));
        if (durationNanos < 0 || thresholdNanos < 0) {
            throw new IllegalArgumentException("Invalid alert timing");
        }
    }
}
