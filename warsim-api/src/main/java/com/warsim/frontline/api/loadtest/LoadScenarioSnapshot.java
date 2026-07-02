package com.warsim.frontline.api.loadtest;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Immutable service snapshot for diagnostics and performance scenario references. */
public record LoadScenarioSnapshot(
    LoadScenarioState state,
    LoadMapId activeMapId,
    LoadScenarioId preparedScenarioId,
    Instant capturedAt,
    int mapsLoaded,
    int scenariosLoaded,
    List<String> validationMessages,
    LoadScenarioMetrics metrics
) {
    public LoadScenarioSnapshot {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(capturedAt, "capturedAt");
        validationMessages = List.copyOf(Objects.requireNonNull(validationMessages, "validationMessages"));
        Objects.requireNonNull(metrics, "metrics");
    }
}
