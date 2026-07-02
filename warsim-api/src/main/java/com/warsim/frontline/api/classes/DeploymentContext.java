package com.warsim.frontline.api.classes;

import com.warsim.frontline.api.roster.TeamSide;
import java.util.Objects;
import java.util.UUID;

public record DeploymentContext(
    UUID playerUuid,
    UUID matchId,
    long lifecycleRevision,
    long deploymentRevision,
    long currentLifeRevision,
    long proposedLifeRevision,
    CombatClassId requestedClass,
    TeamSide teamSide,
    DeploymentReason reason,
    DeploymentTrigger trigger,
    DeploymentSpawnType spawnType,
    String spawnId,
    long startedAtMonotonic,
    long completesAtMonotonic,
    long classConfigurationRevision,
    DeploymentTransactionStage stage
) {
    public DeploymentContext {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(requestedClass, "requestedClass");
        Objects.requireNonNull(teamSide, "teamSide");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(spawnType, "spawnType");
        Objects.requireNonNull(spawnId, "spawnId");
        Objects.requireNonNull(stage, "stage");
        if (proposedLifeRevision != currentLifeRevision + 1) {
            throw new IllegalArgumentException("proposedLifeRevision must equal currentLifeRevision + 1");
        }
    }

    public DeploymentContext stage(DeploymentTransactionStage next) {
        return new DeploymentContext(
            playerUuid, matchId, lifecycleRevision, deploymentRevision, currentLifeRevision,
            proposedLifeRevision, requestedClass, teamSide, reason, trigger, spawnType, spawnId,
            startedAtMonotonic, completesAtMonotonic, classConfigurationRevision, next
        );
    }
}
