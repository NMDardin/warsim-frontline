package com.warsim.frontline.api.combat;

import java.time.Instant;
import java.util.UUID;

public record PlayerKilledEvent(
    UUID matchId,
    long lifecycleRevision,
    UUID killerUuid,
    long killerLifeRevision,
    UUID victimUuid,
    long victimLifeRevision,
    CombatKillClassification classification,
    Instant occurredAt
) implements CombatOutcomeEvent {}
