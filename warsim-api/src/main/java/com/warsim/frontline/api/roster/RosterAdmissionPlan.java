package com.warsim.frontline.api.roster;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record RosterAdmissionPlan(
    UUID playerUuid,
    String currentName,
    UUID matchId,
    long expectedRevision,
    TeamSide teamSide,
    Optional<SquadId> squadId,
    Instant plannedAt,
    AssignmentSource source,
    boolean restoredAfterReconnect,
    boolean idempotent
) {
    public RosterAdmissionPlan {
        squadId = squadId == null ? Optional.empty() : squadId;
    }
}
