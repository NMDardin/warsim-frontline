package com.warsim.frontline.api.redis;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.warsim.frontline.api.ModuleState;
import com.warsim.frontline.api.node.NodeType;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NodeSnapshotTest {
    private static final Instant NOW = Instant.parse("2026-06-21T00:00:00Z");

    @Test void validAvailableBattleIsJoinable() {
        assertTrue(snapshot(NodeAvailability.AVAILABLE, 20, 100, 0, true, NOW)
            .isJoinable(NOW, Duration.ofSeconds(15)));
    }

    @Test void expiredNodeIsNotJoinable() {
        assertFalse(snapshot(NodeAvailability.AVAILABLE, 20, 100, 0, true, NOW.minusSeconds(16))
            .isJoinable(NOW, Duration.ofSeconds(15)));
    }

    @Test void nodeAtExactExpiryBoundaryIsNotJoinable() {
        assertFalse(snapshot(NodeAvailability.AVAILABLE, 20, 100, 0, true, NOW.minusSeconds(15))
            .isJoinable(NOW, Duration.ofSeconds(15)));
    }

    @Test void fullNodeIsNotJoinable() {
        assertFalse(snapshot(NodeAvailability.FULL, 100, 100, 0, true, NOW)
            .isJoinable(NOW, Duration.ofSeconds(15)));
    }

    @Test void drainingNodeIsNotJoinable() {
        assertFalse(snapshot(NodeAvailability.DRAINING, 20, 100, 0, true, NOW)
            .isJoinable(NOW, Duration.ofSeconds(15)));
    }

    @Test void rejectsNegativeOnlinePlayers() {
        assertThrows(IllegalArgumentException.class,
            () -> snapshot(NodeAvailability.AVAILABLE, -1, 100, 0, true, NOW));
    }

    @Test void rejectsOnlineAboveCapacity() {
        assertThrows(IllegalArgumentException.class,
            () -> snapshot(NodeAvailability.AVAILABLE, 101, 100, 0, true, NOW));
    }

    @Test void rejectsNegativeReservedPlayers() {
        assertThrows(IllegalArgumentException.class,
            () -> snapshot(NodeAvailability.AVAILABLE, 20, 100, -1, true, NOW));
    }

    @Test void reservedCapacityCanMakeNodeNotJoinable() {
        assertFalse(snapshot(NodeAvailability.AVAILABLE, 90, 100, 10, true, NOW)
            .isJoinable(NOW, Duration.ofSeconds(15)));
    }

    @Test void lobbyIsNotJoinableAsBattleTarget() {
        NodeSnapshot lobby = new NodeSnapshot(
            "lobby-01", NodeType.LOBBY, UUID.randomUUID(), ModuleState.RUNNING,
            NodeAvailability.AVAILABLE, 1, 100, 0, true, NOW, NOW, 1, "test"
        );
        assertFalse(lobby.isJoinable(NOW, Duration.ofSeconds(15)));
    }

    private static NodeSnapshot snapshot(
        NodeAvailability availability,
        int online,
        int maximum,
        int reserved,
        boolean accepting,
        Instant heartbeat
    ) {
        return new NodeSnapshot(
            "official-war-01", NodeType.OFFICIAL_BATTLE, UUID.randomUUID(), ModuleState.RUNNING,
            availability, online, maximum, reserved, accepting, NOW.minusSeconds(60),
            heartbeat, 1, "0.2.0-SNAPSHOT"
        );
    }
}
