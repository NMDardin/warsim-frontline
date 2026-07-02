package com.warsim.frontline.match.performance;

import java.nio.file.Path;
import java.util.Objects;

public record PerformanceConfiguration(
    boolean enabled,
    int maximumMetrics,
    int samplesPerMetric,
    long warningThresholdNanos,
    long criticalThresholdNanos,
    long alertCooldownMillis,
    int maximumAlerts,
    int maximumReports,
    Path reportDirectory,
    boolean syntheticEnabled,
    int syntheticExecutorQueueCapacity,
    int syntheticDefaultWarmupIterations,
    int syntheticDefaultMeasurementIterations,
    int syntheticMaximumIterations,
    long syntheticMaximumDurationMillis
) {
    public PerformanceConfiguration {
        Objects.requireNonNull(reportDirectory, "reportDirectory");
        if (maximumMetrics < 8 || maximumMetrics > 512
            || samplesPerMetric < 8 || samplesPerMetric > 8192
            || warningThresholdNanos < 0 || criticalThresholdNanos < warningThresholdNanos
            || alertCooldownMillis < 0 || alertCooldownMillis > 300_000
            || maximumAlerts < 0 || maximumAlerts > 512
            || maximumReports < 1 || maximumReports > 100
            || syntheticExecutorQueueCapacity < 1 || syntheticExecutorQueueCapacity > 4
            || syntheticDefaultWarmupIterations < 0
            || syntheticDefaultMeasurementIterations < 1
            || syntheticMaximumIterations < syntheticDefaultMeasurementIterations
            || syntheticMaximumDurationMillis < 100
            || syntheticMaximumDurationMillis > 3_600_000) {
            throw new IllegalArgumentException("Invalid performance configuration");
        }
    }

    public static PerformanceConfiguration disabled(Path dataFolder) {
        return new PerformanceConfiguration(
            false, 128, 512, 25_000_000L, 50_000_000L, 10_000,
            64, 20, dataFolder.resolve("performance-reports"),
            false, 1, 5, 50, 10_000, 30_000
        );
    }
}
