package com.warsim.frontline.api.battle;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record BattleParticipantEvent(
    UUID playerUuid,
    boolean joined,
    Instant occurredAt
) implements BattleRuntimeEvent {
    public BattleParticipantEvent {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
