package com.warsim.frontline.api.classes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PreferredClassSelection(
    UUID playerUuid,
    CombatClassId preferredClass,
    Instant selectedAt
) {
    public PreferredClassSelection {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(preferredClass, "preferredClass");
        Objects.requireNonNull(selectedAt, "selectedAt");
    }
}
