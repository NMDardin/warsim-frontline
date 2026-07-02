package com.warsim.frontline.api.objective;

import java.time.Instant;
import java.util.UUID;

public sealed interface ObjectiveEvent permits ObjectiveCreatedEvent, ObjectiveUnlockedEvent,
    ObjectiveContestedEvent, ObjectiveNeutralizedEvent, ObjectiveCapturedEvent,
    ObjectiveResetEvent {
    UUID matchId();
    ObjectiveId objectiveId();
    Instant occurredAt();
    long revision();
}
