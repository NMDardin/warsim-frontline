package com.warsim.frontline.api.objective;

import java.time.Instant;
import java.util.UUID;

public sealed interface ObjectiveSectorEvent
    permits ObjectiveSectorCompletedEvent, ObjectiveSectorAdvancedEvent {
    UUID matchId();
    long lifecycleRevision();
    Instant occurredAt();
}
