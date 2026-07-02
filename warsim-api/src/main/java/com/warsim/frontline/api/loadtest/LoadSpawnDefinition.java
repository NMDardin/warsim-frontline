package com.warsim.frontline.api.loadtest;

import com.warsim.frontline.api.roster.SquadId;
import com.warsim.frontline.api.roster.TeamSide;
import java.util.Objects;

/** Deterministic slot for a future simulated or real participant. */
public record LoadSpawnDefinition(
    String slotId,
    TeamSide teamSide,
    SquadId squadId,
    LoadCoordinate coordinate,
    LoadScenarioId scenarioId
) {
    public LoadSpawnDefinition {
        Objects.requireNonNull(teamSide, "teamSide");
        Objects.requireNonNull(squadId, "squadId");
        Objects.requireNonNull(coordinate, "coordinate");
        Objects.requireNonNull(scenarioId, "scenarioId");
        if (!slotId.matches("[a-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Invalid load spawn slot id");
        }
    }
}
