package com.warsim.frontline.api.combat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public record CombatDeathRecord(
    UUID matchId,
    long lifecycleRevision,
    UUID targetUuid,
    long targetLifeRevision,
    Optional<CombatDamageSource> killer,
    CombatKillClassification classification,
    List<CombatAssistRecord> assists,
    Instant occurredAt
) {
    public CombatDeathRecord {
        killer = killer == null ? Optional.empty() : killer;
        assists = List.copyOf(assists == null ? List.of() : assists);
    }
}
