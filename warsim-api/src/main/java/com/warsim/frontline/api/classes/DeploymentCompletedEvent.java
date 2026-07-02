package com.warsim.frontline.api.classes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record DeploymentCompletedEvent(
    UUID playerUuid,
    UUID matchId,
    long deploymentRevision,
    long lifeRevision,
    CombatClassId combatClassId,
    DeploymentReason reason,
    DeploymentTrigger trigger,
    Instant occurredAt
) implements ClassDeploymentEvent {
    public DeploymentCompletedEvent {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(combatClassId, "combatClassId");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
