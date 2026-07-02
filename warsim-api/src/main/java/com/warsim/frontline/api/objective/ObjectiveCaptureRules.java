package com.warsim.frontline.api.objective;

import java.util.Objects;

public record ObjectiveCaptureRules(
    int baseSeconds,
    int maximumEffectivePlayers,
    double additionalPlayerRate,
    EmptyBehavior emptyBehavior,
    ContestedBehavior contestedBehavior
) {
    public ObjectiveCaptureRules {
        if (baseSeconds < 5 || baseSeconds > 600) {
            throw new IllegalArgumentException("baseSeconds must be 5-600");
        }
        if (maximumEffectivePlayers < 1 || maximumEffectivePlayers > 10) {
            throw new IllegalArgumentException("maximumEffectivePlayers must be 1-10");
        }
        if (!Double.isFinite(additionalPlayerRate)
            || additionalPlayerRate < 0 || additionalPlayerRate > 2) {
            throw new IllegalArgumentException("additionalPlayerRate must be 0-2");
        }
        Objects.requireNonNull(emptyBehavior, "emptyBehavior");
        Objects.requireNonNull(contestedBehavior, "contestedBehavior");
    }

    public double multiplier(int netPlayers) {
        int effective = Math.min(maximumEffectivePlayers, Math.max(1, netPlayers));
        return 1.0 + (effective - 1) * additionalPlayerRate;
    }
}
