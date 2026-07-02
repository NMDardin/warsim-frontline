package com.warsim.frontline.api.classes;

import java.util.Objects;
import java.util.UUID;

public record LoadoutPreparationRequest(
    UUID playerUuid,
    UUID matchId,
    long lifecycleRevision,
    long deploymentRevision,
    long currentLifeRevision,
    long proposedLifeRevision,
    CombatClassId combatClassId,
    long classConfigurationRevision,
    ClassEquipmentDefinition equipment,
    long createdAtMonotonic,
    long expiresAtMonotonic
) {
    public LoadoutPreparationRequest {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(combatClassId, "combatClassId");
        Objects.requireNonNull(equipment, "equipment");
        if (proposedLifeRevision != currentLifeRevision + 1) {
            throw new IllegalArgumentException("Invalid life revision pair");
        }
        if (expiresAtMonotonic <= createdAtMonotonic) {
            throw new IllegalArgumentException("Token expiry must be after creation");
        }
    }
}
