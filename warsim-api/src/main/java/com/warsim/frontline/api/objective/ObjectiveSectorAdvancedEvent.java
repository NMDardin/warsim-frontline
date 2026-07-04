package com.warsim.frontline.api.objective;

import java.time.Instant;
import java.util.UUID;

public record ObjectiveSectorAdvancedEvent(
    UUID matchId,
    long lifecycleRevision,
    ObjectiveSectorId previousSectorId,
    ObjectiveSectorId currentSectorId,
    Instant occurredAt
) implements ObjectiveSectorEvent {}
