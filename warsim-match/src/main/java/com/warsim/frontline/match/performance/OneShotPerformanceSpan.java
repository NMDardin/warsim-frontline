package com.warsim.frontline.match.performance;

import com.warsim.frontline.api.performance.*;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

final class OneShotPerformanceSpan implements PerformanceSpan {
    private final DefaultPerformanceService service;
    private final PerformanceMetricId metricId;
    private final PerformanceComponent component;
    private final Map<String, String> context;
    private final UUID matchId;
    private final long revision;
    private final long started = System.nanoTime();
    private final AtomicBoolean completed = new AtomicBoolean();

    OneShotPerformanceSpan(
        DefaultPerformanceService service,
        PerformanceMetricId metricId,
        PerformanceComponent component,
        Map<String, String> context,
        UUID matchId,
        long revision
    ) {
        this.service = service;
        this.metricId = metricId;
        this.component = component;
        this.context = Map.copyOf(context);
        this.matchId = matchId;
        this.revision = revision;
    }

    @Override public void success() { complete(true); }

    @Override public void failure() { complete(false); }

    private void complete(boolean successful) {
        if (!completed.compareAndSet(false, true)) return;
        long elapsed = Math.max(0, System.nanoTime() - started);
        try {
            service.record(new PerformanceSample(
                metricId, component, matchId, revision, elapsed, successful,
                Instant.now(), context
            ));
        } catch (RuntimeException ignored) {
            // Sampling must never affect the measured business operation.
        }
    }
}
