package com.warsim.frontline.api.performance;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable timing sample for one metric. */
public record PerformanceSample(
    PerformanceMetricId metricId,
    PerformanceComponent component,
    UUID matchId,
    long lifecycleRevision,
    long durationNanos,
    boolean successful,
    Instant sampledAt,
    Map<String, String> context
) {
    public PerformanceSample {
        Objects.requireNonNull(metricId, "metricId");
        Objects.requireNonNull(component, "component");
        Objects.requireNonNull(sampledAt, "sampledAt");
        context = Map.copyOf(Objects.requireNonNull(context, "context"));
        if (durationNanos < 0) throw new IllegalArgumentException("durationNanos must be non-negative");
        if (context.size() > 16 || context.keySet().stream().anyMatch(k -> !k.matches("[a-z0-9_.-]{1,32}"))) {
            throw new IllegalArgumentException("Invalid performance context");
        }
        if (context.values().stream().anyMatch(v -> v == null || v.length() > 80 || containsSensitiveKey(v))) {
            throw new IllegalArgumentException("Invalid performance context value");
        }
    }

    private static boolean containsSensitiveKey(String value) {
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("password") || lower.contains("secret") || lower.contains("token=");
    }
}
