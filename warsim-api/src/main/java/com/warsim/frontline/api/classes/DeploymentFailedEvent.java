package com.warsim.frontline.api.classes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record DeploymentFailedEvent(
    UUID playerUuid,
    UUID matchId,
    long deploymentRevision,
    DeploymentFailureReason failureReason,
    DeploymentTransactionStage stage,
    String message,
    Instant occurredAt
) implements ClassDeploymentEvent {
    public DeploymentFailedEvent {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(failureReason, "failureReason");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
