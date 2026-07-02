package com.warsim.frontline.api.loadtest;

import java.util.List;
import java.util.Objects;

/** Immutable repeatable load scenario definition. */
public record LoadScenarioDefinition(
    LoadScenarioId scenarioId,
    LoadScenarioType type,
    int schemaVersion,
    LoadMapId mapId,
    LoadMapVersion mapVersion,
    String scenarioVersion,
    String displayName,
    List<String> zoneIds,
    List<LoadSpawnDefinition> slots,
    List<LoadLaneDefinition> lanes,
    String description
) {
    public LoadScenarioDefinition {
        Objects.requireNonNull(scenarioId, "scenarioId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(mapId, "mapId");
        Objects.requireNonNull(mapVersion, "mapVersion");
        zoneIds = List.copyOf(Objects.requireNonNull(zoneIds, "zoneIds"));
        slots = List.copyOf(Objects.requireNonNull(slots, "slots"));
        lanes = List.copyOf(Objects.requireNonNull(lanes, "lanes"));
        if (schemaVersion < 1 || scenarioVersion == null || scenarioVersion.isBlank()
            || displayName == null || displayName.isBlank() || displayName.length() > 80) {
            throw new IllegalArgumentException("Invalid load scenario definition");
        }
    }
}
