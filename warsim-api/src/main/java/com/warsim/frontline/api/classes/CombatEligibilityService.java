package com.warsim.frontline.api.classes;

import java.util.Optional;
import java.util.UUID;

public interface CombatEligibilityService {
    Optional<CombatEligibilitySnapshot> eligibility(UUID playerUuid);

    default boolean alive(UUID playerUuid, UUID matchId, long lifeRevision) {
        return eligibility(playerUuid)
            .filter(value -> value.eligible()
                && value.matchId().equals(matchId)
                && value.lifeRevision() == lifeRevision
                && value.combatState() == PlayerCombatState.ALIVE)
            .isPresent();
    }
}
