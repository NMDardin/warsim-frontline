package com.warsim.frontline.api.objective;

import java.time.Instant;
import java.util.List;

public record ObjectiveSectorSnapshot(
    ObjectiveSectorId sectorId,
    String displayName,
    ObjectiveSectorState state,
    int order,
    List<ObjectiveId> objectiveIds,
    Instant activatedAt,
    Instant completedAt,
    Instant scheduledAdvanceAt
) {
    public ObjectiveSectorSnapshot {
        objectiveIds = List.copyOf(objectiveIds);
    }
}
