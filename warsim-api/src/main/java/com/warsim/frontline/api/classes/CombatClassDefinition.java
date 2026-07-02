package com.warsim.frontline.api.classes;

import java.util.Objects;

public record CombatClassDefinition(
    CombatClassId classId,
    String displayName,
    int maximumPlayers,
    ClassEquipmentDefinition equipment
) {
    public CombatClassDefinition {
        Objects.requireNonNull(classId, "classId");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(equipment, "equipment");
        if (displayName.isBlank() || displayName.length() > 48) {
            throw new IllegalArgumentException("displayName must be 1-48 chars");
        }
        if (maximumPlayers < 0 || maximumPlayers > 100) {
            throw new IllegalArgumentException("maximumPlayers must be 0-100");
        }
    }
}
