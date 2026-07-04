package com.warsim.frontline.api.objective;

import java.time.Instant;
import java.util.UUID;

public record ObjectiveSectorCompletedEvent(
    UUID matchId,
    long lifecycleRevision,
    ObjectiveSectorId sectorId,
    Instant occurredAt
) implements ObjectiveSectorEvent {}
