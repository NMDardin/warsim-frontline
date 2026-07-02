package com.warsim.frontline.api.performance;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable snapshot of current local performance state. */
public record PerformanceSnapshot(
    PerformanceServiceState state,
    String nodeId,
    UUID matchId,
    long lifecycleRevision,
    String matchState,
    Instant capturedAt,
    Map<String, String> configurationSummary,
    List<PerformanceMetricSnapshot> metrics,
    List<PerformanceAlert> alerts,
    PerformanceMetricsSnapshot serviceMetrics,
    SyntheticLoadResult syntheticResult,
    String loadScenarioReference
) {
    public PerformanceSnapshot {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(capturedAt, "capturedAt");
        configurationSummary = Map.copyOf(Objects.requireNonNull(configurationSummary, "configurationSummary"));
        metrics = List.copyOf(Objects.requireNonNull(metrics, "metrics"));
        alerts = List.copyOf(Objects.requireNonNull(alerts, "alerts"));
        Objects.requireNonNull(serviceMetrics, "serviceMetrics");
    }
}
