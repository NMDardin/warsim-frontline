package com.warsim.frontline.api.classes;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import com.warsim.frontline.api.roster.TeamSide;

public record PlayerClassSelection(
    UUID playerUuid,
    UUID matchId,
    Optional<CombatClassId> currentClass,
    Optional<CombatClassId> pendingClass,
    Optional<TeamSide> teamSide,
    PlayerCombatState combatState,
    int successfulDeploymentCount,
    long lifeRevision,
    long deploymentRevision
) {
    public PlayerClassSelection {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(matchId, "matchId");
        currentClass = currentClass == null ? Optional.empty() : currentClass;
        pendingClass = pendingClass == null ? Optional.empty() : pendingClass;
        teamSide = teamSide == null ? Optional.empty() : teamSide;
        Objects.requireNonNull(combatState, "combatState");
        if (successfulDeploymentCount < 0) {
            throw new IllegalArgumentException("successfulDeploymentCount cannot be negative");
        }
        if (lifeRevision < 0 || deploymentRevision < 0) {
            throw new IllegalArgumentException("revisions cannot be negative");
        }
    }

    public DeploymentReason nextDeploymentReason() {
        return successfulDeploymentCount == 0
            ? DeploymentReason.INITIAL_DEPLOYMENT
            : DeploymentReason.RESPAWN;
    }

    public boolean alive() {
        return combatState == PlayerCombatState.ALIVE;
    }
}
