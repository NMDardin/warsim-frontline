package com.warsim.frontline.api.classes;

import com.warsim.frontline.api.roster.TeamSide;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record DeploymentRequest(
    UUID playerUuid,
    UUID matchId,
    long lifecycleRevision,
    CombatClassId requestedClass,
    TeamSide teamSide,
    DeploymentReason reason,
    DeploymentTrigger trigger,
    DeploymentSpawnType spawnType,
    Optional<String> spawnId,
    long delayNanos
) {
    public DeploymentRequest {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(requestedClass, "requestedClass");
        Objects.requireNonNull(teamSide, "teamSide");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(spawnType, "spawnType");
        spawnId = spawnId == null ? Optional.empty() : spawnId;
        if (delayNanos < 0) throw new IllegalArgumentException("delayNanos cannot be negative");
    }
}
