package com.warsim.frontline.api.redis;

import com.warsim.frontline.api.ModuleState;
import com.warsim.frontline.api.node.NodeIds;
import com.warsim.frontline.api.node.NodeType;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record NodeSnapshot(
    String nodeId,
    NodeType nodeType,
    UUID instanceId,
    ModuleState lifecycleState,
    NodeAvailability availability,
    int onlinePlayers,
    int maximumPlayers,
    int reservedPlayers,
    boolean acceptingPlayers,
    Instant startedAt,
    Instant lastHeartbeatAt,
    int protocolVersion,
    String buildVersion
) {
    public NodeSnapshot {
        NodeIds.requireValid(nodeId);
        Objects.requireNonNull(nodeType, "nodeType");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(lifecycleState, "lifecycleState");
        Objects.requireNonNull(availability, "availability");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(lastHeartbeatAt, "lastHeartbeatAt");
        Objects.requireNonNull(buildVersion, "buildVersion");
        if (onlinePlayers < 0 || maximumPlayers < 1 || onlinePlayers > maximumPlayers) {
            throw new IllegalArgumentException("Invalid online player capacity");
        }
        if (reservedPlayers < 0 || onlinePlayers + reservedPlayers > maximumPlayers) {
            throw new IllegalArgumentException("Invalid reserved player capacity");
        }
        if (protocolVersion < 1) {
            throw new IllegalArgumentException("protocolVersion must be positive");
        }
        if (buildVersion.isBlank() || buildVersion.length() > 64) {
            throw new IllegalArgumentException("buildVersion must be 1-64 characters");
        }
    }

    public boolean isJoinable(Instant now, Duration heartbeatTtl) {
        return lastHeartbeatAt.plus(heartbeatTtl).isAfter(now)
            && availability == NodeAvailability.AVAILABLE
            && acceptingPlayers
            && onlinePlayers + reservedPlayers < maximumPlayers
            && (nodeType == NodeType.OFFICIAL_BATTLE || nodeType == NodeType.RENTAL_BATTLE)
            && lifecycleState == ModuleState.RUNNING;
    }
}
