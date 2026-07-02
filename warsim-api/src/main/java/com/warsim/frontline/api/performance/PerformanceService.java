package com.warsim.frontline.api.performance;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Main Paper-owned performance sampler service. */
public interface PerformanceService extends AutoCloseable {
    PerformanceServiceState state();

    PerformanceSpan startSpan(
        PerformanceMetricId metricId,
        PerformanceComponent component,
        Map<String, String> context
    );

    void record(PerformanceSample sample);

    AutoCloseable registerContributor(PerformanceContributor contributor);

    PerformanceSnapshot snapshot(Optional<PerformanceComponent> component);

    List<PerformanceAlert> alerts();

    List<SyntheticLoadScenario> syntheticScenarios();

    Optional<SyntheticLoadScenario> syntheticScenario(String id);

    SyntheticDryRunPlan dryRun(String scenarioId);

    boolean startSynthetic(String scenarioId, int measurementIterations);

    Optional<SyntheticLoadResult> syntheticStatus();

    boolean cancelSynthetic();

    PerformanceExportResult exportReport();

    void reset();

    void updateMatchContext(UUID matchId, long lifecycleRevision, String matchState);
}
