package com.warsim.frontline.api.performance;

import java.util.OptionalLong;

/** Nearest-rank percentile values for one bounded sample window. */
public record PerformancePercentiles(
    OptionalLong p50Nanos,
    OptionalLong p95Nanos,
    OptionalLong p99Nanos
) {
    public PerformancePercentiles {
        if (p50Nanos == null || p95Nanos == null || p99Nanos == null) {
            throw new NullPointerException("percentiles");
        }
    }

    public static PerformancePercentiles unavailable() {
        return new PerformancePercentiles(OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty());
    }
}
