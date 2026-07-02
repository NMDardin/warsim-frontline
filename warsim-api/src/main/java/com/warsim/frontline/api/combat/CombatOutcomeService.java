package com.warsim.frontline.api.combat;

import java.util.Optional;
import java.util.UUID;

public interface CombatOutcomeService {
    DamageCorrelationResult beginDamageCorrelation(DamageCorrelationRequest request);

    boolean completeDamageCorrelation(UUID correlationId, double effectiveDamage, long completedAtMonotonic);

    boolean cancelDamageCorrelation(UUID correlationId, String reason, long cancelledAtMonotonic);

    Optional<PlayerCombatStatistics> statistics(UUID playerUuid);

    CombatOutcomeSnapshot snapshot();

    void clearPlayer(UUID playerUuid);
}
