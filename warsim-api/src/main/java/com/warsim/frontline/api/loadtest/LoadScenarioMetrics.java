package com.warsim.frontline.api.loadtest;

import java.time.Instant;

/** Local counters for load scenario diagnostics. */
public record LoadScenarioMetrics(
    long validations,
    long validationFailures,
    long preparations,
    long preparationFailures,
    long cleans,
    long cleanFailures,
    Instant lastValidationAt,
    Instant lastPreparationAt
) {}
