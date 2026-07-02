package com.warsim.frontline.api.combat;

import java.time.Instant;
import java.util.UUID;

public record PlayerAssistedEvent(
    UUID matchId,
    long lifecycleRevision,
    UUID assisterUuid,
    UUID victimUuid,
    double damage,
    Instant occurredAt
) implements CombatOutcomeEvent {}
