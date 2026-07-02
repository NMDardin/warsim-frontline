package com.warsim.frontline.api.battle;

import java.time.Instant;
import java.util.Objects;

public record BattleRuntimeClosedEvent(Instant occurredAt) implements BattleRuntimeEvent {
    public BattleRuntimeClosedEvent {
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
