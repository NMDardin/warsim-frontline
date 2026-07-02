package com.warsim.frontline.api.classes;

import java.util.Objects;
import java.util.UUID;

public record LoadoutPreparationToken(
    UUID tokenId,
    UUID playerUuid,
    UUID matchId,
    long lifecycleRevision,
    long deploymentRevision,
    long currentLifeRevision,
    long proposedLifeRevision,
    CombatClassId combatClassId,
    long classConfigurationRevision,
    UUID providerInstanceId,
    long createdAtMonotonic,
    long expiresAtMonotonic
) {
    public LoadoutPreparationToken {
        Objects.requireNonNull(tokenId, "tokenId");
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(combatClassId, "combatClassId");
        Objects.requireNonNull(providerInstanceId, "providerInstanceId");
        if (proposedLifeRevision != currentLifeRevision + 1) {
            throw new IllegalArgumentException("Invalid life revision pair");
        }
    }
}
