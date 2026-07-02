package com.warsim.frontline.api.classes;

import java.util.Objects;
import java.util.UUID;

public record ManagedLoadoutClearRequest(
    UUID playerUuid,
    UUID matchId,
    long lifecycleRevision,
    long deploymentRevision,
    long lifeRevision,
    UUID providerInstanceId,
    String reason
) {
    public ManagedLoadoutClearRequest {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(providerInstanceId, "providerInstanceId");
        Objects.requireNonNull(reason, "reason");
    }
}
