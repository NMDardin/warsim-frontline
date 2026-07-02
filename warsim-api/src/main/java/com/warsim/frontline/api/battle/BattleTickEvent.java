package com.warsim.frontline.api.battle;

import java.time.Instant;
import java.util.Objects;

public record BattleTickEvent(
    BattleRuntimeSnapshot snapshot,
    long monotonicNanos,
    long tick,
    Instant occurredAt
) implements BattleRuntimeEvent {
    public BattleTickEvent {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
