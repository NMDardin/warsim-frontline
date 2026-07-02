package com.warsim.frontline.api.roster;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record TeamAssignment(
    UUID playerUuid,
    String currentName,
    UUID matchId,
    TeamSide teamSide,
    Optional<SquadId> squadId,
    SquadRole squadRole,
    Instant assignedAt,
    AssignmentSource source,
    boolean restoredAfterReconnect,
    boolean connected
) {
    public TeamAssignment {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(teamSide, "teamSide");
        squadId = squadId == null ? Optional.empty() : squadId;
        Objects.requireNonNull(squadRole, "squadRole");
        Objects.requireNonNull(assignedAt, "assignedAt");
        Objects.requireNonNull(source, "source");
        if (currentName == null || !currentName.matches("[A-Za-z0-9_]{1,16}")) {
            throw new IllegalArgumentException("Invalid player name");
        }
    }
}
