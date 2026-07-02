package com.warsim.frontline.network.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.warsim.frontline.api.ModuleState;
import com.warsim.frontline.api.node.NodeType;
import com.warsim.frontline.api.redis.ControlMessage;
import com.warsim.frontline.api.redis.ControlMessageResult;
import com.warsim.frontline.api.redis.ControlMessageType;
import com.warsim.frontline.api.redis.NodeAvailability;
import com.warsim.frontline.api.redis.NodeSnapshot;
import com.warsim.frontline.api.redis.RedisState;
import io.lettuce.core.Consumer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.XAddArgs;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XReadArgs;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedisControlPlaneIntegrationTest {
    private static GenericContainer<?> redis;
    private static LettuceRedisService service;
    private static RedisClient inspectionClient;
    private static io.lettuce.core.api.StatefulRedisConnection<String, String> inspection;
    private static RedisConfiguration configuration;
    private static final String NODE = "official-war-01";

    @BeforeAll
    static void startRedis() {
        Assumptions.assumeTrue(
            DockerClientFactory.instance().isDockerAvailable(),
            "Docker is unavailable; Redis integration tests were skipped"
        );
        redis = new GenericContainer<>(DockerImageName.parse("redis:8-alpine"))
            .withExposedPorts(6379);
        redis.start();
        configuration = enabled("redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));
        service = new LettuceRedisService(configuration, NODE, UUID.randomUUID());
        service.start().join();
        inspectionClient = RedisClient.create(configuration.uri());
        inspection = inspectionClient.connect();
    }

    @AfterAll
    static void stopRedis() {
        if (service != null) service.close();
        if (inspection != null) inspection.close();
        if (inspectionClient != null) inspectionClient.shutdown();
        if (redis != null) redis.stop();
    }

    @Test @Order(1) void realConnectionAndPing() {
        assertEquals(RedisState.HEALTHY, service.state());
        assertEquals("PONG", inspection.sync().ping());
    }

    @Test @Order(2) void nodeHeartbeatWritesHash() {
        service.publishHeartbeat(snapshot(Instant.now())).join();
        assertEquals(NODE, inspection.sync().hget(nodeKey(), "nodeId"));
    }

    @Test @Order(3) void nodeHashHasTtl() {
        assertTrue(inspection.sync().pttl(nodeKey()) > 0);
    }

    @Test @Order(4) void lastSeenIndexIsWritten() {
        assertNotNull(inspection.sync().zscore(indexKey(), NODE));
    }

    @Test @Order(5) void directoryReadsHeartbeat() {
        assertTrue(service.findNode(NODE).join().isPresent());
        assertTrue(service.listActiveNodes().join().stream().anyMatch(node -> NODE.equals(node.nodeId())));
    }

    @Test @Order(6) void expiredHashIsFiltered() {
        inspection.sync().del(nodeKey());
        assertFalse(service.findNode(NODE).join().isPresent());
        service.publishHeartbeat(snapshot(Instant.now())).join();
    }

    @Test @Order(7) void nodePingProducesPong() {
        assertTrue(service.ping(NODE, Duration.ofSeconds(5)).join().toMillis() >= 0);
    }

    @Test @Order(8) void consumerGroupExists() {
        assertFalse(inspection.sync().xinfoGroups(streamKey()).isEmpty());
    }

    @Test @Order(9) void successfulMessageIsAcknowledged() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        service.registerHandler(ControlMessageType.NODE_REFRESH_REQUEST, message -> {
            calls.incrementAndGet();
            return java.util.concurrent.CompletableFuture.completedFuture(ControlMessageResult.ACKNOWLEDGED);
        });
        service.publish(message(ControlMessageType.NODE_REFRESH_REQUEST, UUID.randomUUID())).join();
        await(() -> calls.get() == 1);
        assertEquals(1, calls.get());
    }

    @SuppressWarnings("unchecked")
    @Test @Order(10) void pendingMessagesCanBeClaimed() throws Exception {
        String target = "claim-node";
        String stream = configuration.namespace() + ":streams:control:" + target;
        inspection.sync().xgroupCreate(
            XReadArgs.StreamOffset.from(stream, "0-0"),
            "warsim-control",
            new XGroupCreateArgs().mkstream(true)
        );
        ControlMessage pending = message(
            ControlMessageType.NODE_REFRESH_REQUEST,
            UUID.randomUUID(),
            target
        );
        String encoded = new ControlMessageCodec().encode(
            pending,
            configuration.maximumPayloadBytes()
        );
        inspection.sync().xadd(stream, new XAddArgs(), Map.of("message", encoded));
        inspection.sync().xreadgroup(
            Consumer.from("warsim-control", "crashed-consumer"),
            XReadArgs.StreamOffset.lastConsumed(stream)
        );
        Thread.sleep(150);

        AtomicInteger calls = new AtomicInteger();
        LettuceRedisService claimant = new LettuceRedisService(
            enabledWithClaim(configuration.uri(), 100),
            target,
            UUID.randomUUID()
        );
        claimant.registerHandler(ControlMessageType.NODE_REFRESH_REQUEST, control -> {
            calls.incrementAndGet();
            return java.util.concurrent.CompletableFuture.completedFuture(
                ControlMessageResult.ACKNOWLEDGED
            );
        });
        claimant.start().join();
        await(() -> calls.get() == 1);
        claimant.close();
        assertEquals(1, calls.get());
    }

    @Test @Order(11) void dedupKeyIsWrittenAfterSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        service.publish(message(ControlMessageType.NODE_REFRESH_REQUEST, id)).join();
        await(() -> inspection.sync().exists(dedupKey(id)) == 1);
        assertEquals(1, inspection.sync().exists(dedupKey(id)));
    }

    @Test @Order(12) void duplicateMessageDoesNotExecuteTwice() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        service.registerHandler(ControlMessageType.NODE_REFRESH_RESPONSE, message -> {
            calls.incrementAndGet();
            return java.util.concurrent.CompletableFuture.completedFuture(ControlMessageResult.ACKNOWLEDGED);
        });
        UUID id = UUID.randomUUID();
        ControlMessage duplicate = message(ControlMessageType.NODE_REFRESH_RESPONSE, id);
        service.publish(duplicate).join();
        await(() -> calls.get() == 1);
        service.publish(duplicate).join();
        Thread.sleep(500);
        assertEquals(1, calls.get());
    }

    @Test @Order(13) void failedMessageRetries() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        service.registerHandler(ControlMessageType.NODE_REFRESH_REQUEST, message ->
            java.util.concurrent.CompletableFuture.completedFuture(
                calls.incrementAndGet() < 2 ? ControlMessageResult.RETRY : ControlMessageResult.ACKNOWLEDGED
            ));
        service.publish(message(ControlMessageType.NODE_REFRESH_REQUEST, UUID.randomUUID())).join();
        await(() -> calls.get() >= 2);
        assertTrue(service.metrics().retriedMessages() >= 1);
    }

    @Test @Order(14) void attemptsBeyondLimitEnterDeadLetter() throws Exception {
        service.registerHandler(ControlMessageType.NODE_REFRESH_REQUEST, message ->
            java.util.concurrent.CompletableFuture.completedFuture(ControlMessageResult.RETRY));
        service.publish(message(ControlMessageType.NODE_REFRESH_REQUEST, UUID.randomUUID())).join();
        await(() -> inspection.sync().xlen(deadLetterKey()) > 0);
        assertTrue(inspection.sync().xlen(deadLetterKey()) > 0);
    }

    @Test @Order(15) void acknowledgedCountIncreases() {
        assertTrue(service.metrics().acknowledgedMessages() > 0);
    }

    @Test @Order(16) void redisRestartRecoversAfterHeartbeatProbe() throws Exception {
        redis.getDockerClient().restartContainerCmd(redis.getContainerId()).exec();
        await(() -> {
            try {
                service.publishHeartbeat(snapshot(Instant.now())).join();
                return service.state() == RedisState.HEALTHY;
            } catch (RuntimeException ignored) {
                return false;
            }
        });
        assertEquals(RedisState.HEALTHY, service.state());
    }

    @Test @Order(17) void clientCloseStopsService() throws Exception {
        service.close();
        await(() -> service.state() == RedisState.STOPPED);
        assertEquals(RedisState.STOPPED, service.state());
    }

    private static NodeSnapshot snapshot(Instant heartbeat) {
        return new NodeSnapshot(
            NODE, NodeType.OFFICIAL_BATTLE, UUID.randomUUID(), ModuleState.RUNNING,
            NodeAvailability.AVAILABLE, 10, 100, 0, true, heartbeat.minusSeconds(30),
            heartbeat, 1, "integration"
        );
    }

    private static ControlMessage message(ControlMessageType type, UUID id) {
        return message(type, id, NODE);
    }

    private static ControlMessage message(ControlMessageType type, UUID id, String target) {
        Instant now = Instant.now();
        return new ControlMessage(
            1, id, type, NODE, target, UUID.randomUUID(), now, now.plusSeconds(30),
            1, null, new byte[0]
        );
    }

    private static RedisConfiguration enabled(String uri) {
        RedisConfiguration d = RedisConfiguration.defaults();
        return new RedisConfiguration(
            true, uri, "", "", 0, d.namespace(), false, true,
            d.connectionTimeoutMillis(), d.reconnectDelayMillis(), d.heartbeatIntervalMillis(),
            d.heartbeatTtlMillis(), true, 200, d.streamBatchSize(), d.claimIdleMillis(),
            d.messageTtlMillis(), d.maximumAttempts(), d.deduplicationTtlSeconds(),
            d.maximumPayloadBytes()
        );
    }

    private static RedisConfiguration enabledWithClaim(String uri, long claimIdleMillis) {
        RedisConfiguration d = enabled(uri);
        return new RedisConfiguration(
            d.enabled(), d.uri(), d.username(), d.password(), d.database(), d.namespace(),
            d.tlsEnabled(), d.verifyHostname(), d.connectionTimeoutMillis(),
            d.reconnectDelayMillis(), d.heartbeatIntervalMillis(), d.heartbeatTtlMillis(),
            d.streamsEnabled(), d.streamBlockMillis(), d.streamBatchSize(), claimIdleMillis,
            d.messageTtlMillis(), d.maximumAttempts(), d.deduplicationTtlSeconds(),
            d.maximumPayloadBytes()
        );
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(condition.getAsBoolean());
    }

    private static String nodeKey() { return configuration.namespace() + ":nodes:" + NODE; }
    private static String indexKey() { return configuration.namespace() + ":nodes:last_seen"; }
    private static String streamKey() { return configuration.namespace() + ":streams:control:" + NODE; }
    private static String deadLetterKey() { return configuration.namespace() + ":streams:dead_letter:" + NODE; }
    private static String dedupKey(UUID id) { return configuration.namespace() + ":dedup:" + NODE + ":" + id; }
}
