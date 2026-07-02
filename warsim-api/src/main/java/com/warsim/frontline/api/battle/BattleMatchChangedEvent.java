package com.warsim.frontline.api.battle;

import java.time.Instant;
import java.util.Objects;

public record BattleMatchChangedEvent(
    BattleRuntimeSnapshot previous,
    BattleRuntimeSnapshot current,
    Instant occurredAt
) implements BattleRuntimeEvent {
    public BattleMatchChangedEvent {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
