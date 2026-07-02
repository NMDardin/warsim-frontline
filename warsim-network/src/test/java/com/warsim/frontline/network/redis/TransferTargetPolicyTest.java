package com.warsim.frontline.network.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.warsim.frontline.api.ModuleState;
import com.warsim.frontline.api.node.NodeType;
import com.warsim.frontline.api.redis.NodeAvailability;
import com.warsim.frontline.api.redis.NodeSnapshot;
import com.warsim.frontline.api.redis.RedisState;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransferTargetPolicyTest {
    private static final Instant NOW = Instant.parse("2026-06-21T00:00:00Z");
    private final TransferTargetPolicy policy = new TransferTargetPolicy();

    @Test void healthyRedisAllowsAvailableCapacity() {
        assertEquals(TransferTargetPolicy.Decision.ALLOW,
            policy.assess(RedisState.HEALTHY, Optional.of(node(NodeAvailability.AVAILABLE, 50, true)),
                NOW, Duration.ofSeconds(15)));
    }

    @Test void unavailableRedisUsesFallback() {
        assertEquals(TransferTargetPolicy.Decision.FALLBACK,
            policy.assess(RedisState.UNAVAILABLE, Optional.empty(), NOW, Duration.ofSeconds(15)));
    }

    @Test void healthyRedisRejectsFullTarget() {
        assertEquals(TransferTargetPolicy.Decision.FULL,
            policy.assess(RedisState.HEALTHY, Optional.of(node(NodeAvailability.FULL, 100, true)),
                NOW, Duration.ofSeconds(15)));
    }

    @Test void healthyRedisRejectsDrainingTarget() {
        assertEquals(TransferTargetPolicy.Decision.DRAINING,
            policy.assess(RedisState.HEALTHY, Optional.of(node(NodeAvailability.DRAINING, 50, false)),
                NOW, Duration.ofSeconds(15)));
    }

    @Test void heartbeatAtExactExpiryBoundaryIsOffline() {
        NodeSnapshot expired = new NodeSnapshot(
            "official-war-01", NodeType.OFFICIAL_BATTLE, UUID.randomUUID(), ModuleState.RUNNING,
            NodeAvailability.AVAILABLE, 50, 100, 0, true, NOW.minusSeconds(60),
            NOW.minusSeconds(15), 1, "test"
        );
        assertEquals(
            TransferTargetPolicy.Decision.OFFLINE,
            policy.assess(RedisState.HEALTHY, Optional.of(expired), NOW, Duration.ofSeconds(15))
        );
    }

    private static NodeSnapshot node(NodeAvailability availability, int online, boolean accepting) {
        return new NodeSnapshot(
            "official-war-01", NodeType.OFFICIAL_BATTLE, UUID.randomUUID(), ModuleState.RUNNING,
            availability, online, 100, 0, accepting, NOW.minusSeconds(60), NOW, 1, "test"
        );
    }
}
