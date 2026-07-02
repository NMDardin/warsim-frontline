package com.warsim.frontline.api.combat;

import java.time.Instant;
import java.util.UUID;

public record PlayerDiedEvent(
    UUID matchId,
    long lifecycleRevision,
    UUID playerUuid,
    long lifeRevision,
    CombatKillClassification classification,
    Instant occurredAt
) implements CombatOutcomeEvent {}
