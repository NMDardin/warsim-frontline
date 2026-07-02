package com.warsim.frontline.api.performance;

import java.time.Instant;
import java.util.List;

/** Service-level performance sampler counters. */
public record PerformanceMetricsSnapshot(
    int metricWindows,
    int maximumMetricWindows,
    long totalSamples,
    long rejectedSamples,
    long droppedSamples,
    long alertCount,
    long reportsExported,
    long syntheticRunsStarted,
    long syntheticRunsCompleted,
    long syntheticRunsFailed,
    long syntheticRunsCancelled,
    Instant lastSampleAt
) {
    public PerformanceMetricsSnapshot {
        if (metricWindows < 0 || maximumMetricWindows < 1 || totalSamples < 0) {
            throw new IllegalArgumentException("Invalid performance service metrics");
        }
    }
}
