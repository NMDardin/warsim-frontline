package com.warsim.frontline.api.classes;

import java.time.Instant;
import java.util.UUID;

public sealed interface ClassDeploymentEvent
    permits ClassSelectedEvent, DeploymentStartedEvent, DeploymentCompletedEvent,
    DeploymentFailedEvent {
    UUID playerUuid();
    UUID matchId();
    Instant occurredAt();
}
