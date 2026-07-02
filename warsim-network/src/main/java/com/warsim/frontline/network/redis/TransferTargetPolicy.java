package com.warsim.frontline.network.redis;

import com.warsim.frontline.api.redis.NodeAvailability;
import com.warsim.frontline.api.redis.NodeSnapshot;
import com.warsim.frontline.api.redis.RedisState;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public final class TransferTargetPolicy {
    public Decision assess(
        RedisState redisState,
        Optional<NodeSnapshot> snapshot,
        Instant now,
        Duration heartbeatTtl
    ) {
        if (redisState != RedisState.HEALTHY) {
            return Decision.FALLBACK;
        }
        if (snapshot.isEmpty()
            || !snapshot.get().lastHeartbeatAt().plus(heartbeatTtl).isAfter(now)) {
            return Decision.OFFLINE;
        }
        NodeSnapshot node = snapshot.get();
        if (node.availability() == NodeAvailability.FULL
            || node.onlinePlayers() + node.reservedPlayers() >= node.maximumPlayers()) {
            return Decision.FULL;
        }
        if (node.availability() == NodeAvailability.DRAINING
            || node.availability() == NodeAvailability.STOPPING
            || !node.acceptingPlayers()) {
            return Decision.DRAINING;
        }
        return node.availability() == NodeAvailability.AVAILABLE
            ? Decision.ALLOW : Decision.UNKNOWN;
    }

    public enum Decision {
        ALLOW,
        FALLBACK,
        OFFLINE,
        FULL,
        DRAINING,
        UNKNOWN
    }
}
