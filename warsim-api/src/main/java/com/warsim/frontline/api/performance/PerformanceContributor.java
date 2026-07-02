package com.warsim.frontline.api.performance;

import java.util.List;

/** Optional extension that contributes read-only metrics to the main sampler. */
public interface PerformanceContributor {
    String name();

    List<PerformanceMetricSnapshot> snapshotMetrics();
}
