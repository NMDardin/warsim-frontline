package com.warsim.frontline.api.battle;

import java.time.Instant;

/**
 * Marker for immutable local battle lifecycle notifications.
 */
public sealed interface BattleRuntimeEvent permits
    BattleTickEvent, BattleMatchChangedEvent, BattleParticipantEvent, BattleRuntimeClosedEvent {
    Instant occurredAt();
}
