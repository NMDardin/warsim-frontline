package com.warsim.frontline.api.objective;

import com.warsim.frontline.api.roster.TeamSide;
import java.util.Objects;
import java.util.UUID;

public record ObjectivePlayerPresence(
    UUID playerUuid,
    TeamSide teamSide,
    String worldName,
    double x,
    double y,
    double z
) {
    public ObjectivePlayerPresence {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(teamSide, "teamSide");
        Objects.requireNonNull(worldName, "worldName");
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Presence coordinates must be finite");
        }
    }
}
