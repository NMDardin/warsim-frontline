package com.warsim.frontline.api.combat;

import java.time.Instant;
import java.util.UUID;

public sealed interface CombatOutcomeEvent
    permits PlayerKilledEvent, PlayerAssistedEvent, PlayerDiedEvent {
    UUID matchId();
    long lifecycleRevision();
    Instant occurredAt();
}
