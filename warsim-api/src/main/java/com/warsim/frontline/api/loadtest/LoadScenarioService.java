package com.warsim.frontline.api.loadtest;

import java.util.List;
import java.util.Optional;

/** Load-test map and repeatable scenario diagnostics service. */
public interface LoadScenarioService extends AutoCloseable {
    LoadScenarioSnapshot snapshot();

    List<LoadMapDefinition> maps();

    List<LoadScenarioDefinition> scenarios();

    Optional<LoadScenarioDefinition> scenario(LoadScenarioId scenarioId);

    LoadScenarioValidationResult validate(LoadMapId mapId);

    LoadScenarioPreparationResult prepare(LoadScenarioId scenarioId);

    LoadScenarioPreparationResult clean();
}
