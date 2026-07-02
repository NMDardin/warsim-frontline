package com.warsim.frontline.network.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.warsim.frontline.api.redis.ControlMessage;
import com.warsim.frontline.api.redis.ControlMessageResult;
import com.warsim.frontline.api.redis.ControlMessageType;
import com.warsim.frontline.api.redis.NodeAvailability;
import com.warsim.frontline.api.redis.NodeSnapshot;
import com.warsim.frontline.api.redis.RedisState;
import com.warsim.frontline.api.ModuleState;
import com.warsim.frontline.api.node.NodeType;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class LettuceRedisServiceTest {
    @Test void disabledServiceStartsDisabled() {
        LettuceRedisService service = service();
        service.start().join();
        assertEquals(RedisState.DISABLED, service.state());
        service.close();
    }

    @Test void repeatedStartIsSafeWhenDisabled() {
        LettuceRedisService service = service();
        service.start().join();
        service.start().join();
        assertEquals(RedisState.DISABLED, service.state());
        service.close();
    }

    @Test void repeatedCloseIsSafe() {
        LettuceRedisService service = service();
        service.close();
        service.close();
        assertEquals(RedisState.DISABLED, service.state());
    }

    @Test void invalidEnabledConfigurationEntersFailedState() {
        RedisConfiguration invalid = RedisConfiguration.defaults()
            .withEnabled(true)
            .withConnection("http://127.0.0.1:6379", "", "");
        LettuceRedisService service = new LettuceRedisService(
            invalid,
            "lobby-01",
            UUID.randomUUID()
        );
        assertThrows(CompletionException.class, () -> service.start().join());
        assertEquals(RedisState.FAILED, service.state());
        service.close();
    }

    @Test void closedServiceRejectsNewMessages() {
        LettuceRedisService service = service();
        service.close();
        CompletionException exception = assertThrows(
            CompletionException.class,
            () -> service.publish(message()).join()
        );
        assertEquals("Redis message bus unavailable", exception.getCause().getMessage());
    }

    @Test void metricsSnapshotIsSafeUnderConcurrentUpdates() throws Exception {
        LettuceRedisService service = service();
        Thread[] readers = new Thread[8];
        for (int index = 0; index < readers.length; index++) {
            readers[index] = Thread.startVirtualThread(() -> {
                for (int count = 0; count < 1000; count++) {
                    service.metrics();
                }
            });
        }
        for (Thread reader : readers) {
            reader.join();
        }
        service.close();
    }

    @Test void heartbeatCanProbeRecoveryFromDegradedState() {
        assertTrue(LettuceRedisService.canAttemptHeartbeat(RedisState.DEGRADED, true, false));
        assertTrue(LettuceRedisService.canAttemptHeartbeat(RedisState.UNAVAILABLE, true, false));
        assertFalse(LettuceRedisService.canAttemptHeartbeat(RedisState.DEGRADED, false, false));
        assertFalse(LettuceRedisService.canAttemptHeartbeat(RedisState.HEALTHY, true, true));
    }

    @Test void directoryRejectsStoppingAndExpiredNodes() {
        Instant now = Instant.parse("2026-06-21T00:00:00Z");
        assertFalse(LettuceRedisService.isDirectoryVisible(
            snapshot(ModuleState.STOPPING, NodeAvailability.STOPPING, now),
            now,
            Duration.ofSeconds(15)
        ));
        assertFalse(LettuceRedisService.isDirectoryVisible(
            snapshot(ModuleState.RUNNING, NodeAvailability.AVAILABLE, now.minusSeconds(15)),
            now,
            Duration.ofSeconds(15)
        ));
        assertTrue(LettuceRedisService.isDirectoryVisible(
            snapshot(ModuleState.RUNNING, NodeAvailability.AVAILABLE, now),
            now,
            Duration.ofSeconds(15)
        ));
    }

    @Test void onlyBusyGroupCreationFailureIsIgnorable() {
        assertTrue(LettuceRedisService.isConsumerGroupAlreadyPresent(
            new CompletionException(new IllegalStateException("BUSYGROUP Consumer Group name already exists"))
        ));
        assertFalse(LettuceRedisService.isConsumerGroupAlreadyPresent(
            new CompletionException(new IllegalStateException("NOAUTH Authentication required"))
        ));
    }

    @Test void synchronousHandlerFailureBecomesRetryResult() {
        ControlMessage controlMessage = message();
        assertEquals(
            ControlMessageResult.RETRY,
            LettuceRedisService.invokeHandler(
                ignored -> {
                    throw new IllegalStateException("handler failure");
                },
                controlMessage,
                1000
            ).join()
        );
    }

    @Test void expiredPayloadFailureIsClassifiedSeparately() {
        assertTrue(LettuceRedisService.isExpiredMessageFailure(
            new IllegalArgumentException("Invalid control message: Control message expired")
        ));
        assertFalse(LettuceRedisService.isExpiredMessageFailure(
            new IllegalArgumentException("Invalid control message magic")
        ));
    }

    private static LettuceRedisService service() {
        return new LettuceRedisService(
            RedisConfiguration.defaults(), "lobby-01", UUID.randomUUID()
        );
    }

    private static ControlMessage message() {
        Instant now = Instant.now();
        return new ControlMessage(
            1, UUID.randomUUID(), ControlMessageType.NODE_PING, "lobby-01",
            "official-war-01", UUID.randomUUID(), now, now.plusSeconds(5),
            1, null, new byte[0]
        );
    }

    private static NodeSnapshot snapshot(
        ModuleState state,
        NodeAvailability availability,
        Instant heartbeat
    ) {
        return new NodeSnapshot(
            "official-war-01", NodeType.OFFICIAL_BATTLE, UUID.randomUUID(), state,
            availability, 10, 100, 0, availability == NodeAvailability.AVAILABLE,
            heartbeat.minusSeconds(30), heartbeat, 1, "test"
        );
    }
}
