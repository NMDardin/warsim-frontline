package com.warsim.frontline.api.classes;

import java.util.UUID;

public record CombatEligibilitySnapshot(
    UUID playerUuid,
    UUID matchId,
    long lifecycleRevision,
    long lifeRevision,
    PlayerCombatState combatState,
    boolean eligible
) {
}
