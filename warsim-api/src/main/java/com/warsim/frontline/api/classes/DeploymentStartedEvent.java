package com.warsim.frontline.api.classes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record DeploymentStartedEvent(
    UUID playerUuid,
    UUID matchId,
    long deploymentRevision,
    long currentLifeRevision,
    long proposedLifeRevision,
    CombatClassId combatClassId,
    DeploymentReason reason,
    DeploymentTrigger trigger,
    Instant occurredAt
) implements ClassDeploymentEvent {
    public DeploymentStartedEvent {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(combatClassId, "combatClassId");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
