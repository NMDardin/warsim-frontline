package com.warsim.frontline.api.combat;

import java.util.Optional;
import java.util.UUID;

public interface CombatOutcomeService {
    DamageCorrelationResult beginDamageCorrelation(DamageCorrelationRequest request);

    boolean completeDamageCorrelation(UUID correlationId, double effectiveDamage, long completedAtMonotonic);

    boolean cancelDamageCorrelation(UUID correlationId, String reason, long cancelledAtMonotonic);

    default long damageCorrelationTtlNanos() {
        return 2_000_000_000L;
    }

    Optional<PlayerCombatStatistics> statistics(UUID playerUuid);

    CombatOutcomeSnapshot snapshot();

    void clearPlayer(UUID playerUuid);
}
