package com.warsim.frontline.api.loadtest;

import java.util.List;

/** Validation result for maps or scenarios. */
public record LoadScenarioValidationResult(
    boolean successful,
    String message,
    List<String> details
) {
    public LoadScenarioValidationResult {
        details = List.copyOf(details);
    }
}
