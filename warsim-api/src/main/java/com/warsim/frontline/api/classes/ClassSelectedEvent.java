package com.warsim.frontline.api.classes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ClassSelectedEvent(
    UUID playerUuid,
    UUID matchId,
    CombatClassId combatClassId,
    boolean pendingOnly,
    Instant occurredAt
) implements ClassDeploymentEvent {
    public ClassSelectedEvent {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(combatClassId, "combatClassId");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
