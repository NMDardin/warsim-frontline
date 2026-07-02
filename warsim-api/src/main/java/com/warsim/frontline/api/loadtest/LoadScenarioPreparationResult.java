package com.warsim.frontline.api.loadtest;

/** Result of preparing or cleaning a scenario context. */
public record LoadScenarioPreparationResult(
    boolean successful,
    String message,
    LoadScenarioSnapshot snapshot
) {}
