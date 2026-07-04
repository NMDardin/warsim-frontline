package com.warsim.frontline.api.objective;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record ObjectiveSectorDefinition(
    ObjectiveSectorId sectorId,
    String displayName,
    int order,
    List<ObjectiveId> objectiveIds
) {
    public ObjectiveSectorDefinition {
        Objects.requireNonNull(sectorId, "sectorId");
        Objects.requireNonNull(displayName, "displayName");
        objectiveIds = List.copyOf(objectiveIds);
        if (displayName.isBlank() || displayName.length() > 32) {
            throw new IllegalArgumentException("sector displayName must be 1-32 characters");
        }
        if (objectiveIds.isEmpty()) {
            throw new IllegalArgumentException("sector objectiveIds must not be empty");
        }
        if (new HashSet<>(objectiveIds).size() != objectiveIds.size()) {
            throw new IllegalArgumentException("Duplicate objective ID in sector");
        }
    }
}
