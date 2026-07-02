package com.warsim.frontline.network.redis;

import com.warsim.frontline.api.ModuleState;
import com.warsim.frontline.api.node.NodeIds;
import com.warsim.frontline.api.node.NodeType;
import com.warsim.frontline.api.redis.ControlMessage;
import com.warsim.frontline.api.redis.ControlMessageHandler;
import com.warsim.frontline.api.redis.ControlMessageResult;
import com.warsim.frontline.api.redis.ControlMessageType;
import com.warsim.frontline.api.redis.NodeAvailability;
import com.warsim.frontline.api.redis.NodeSnapshot;
import com.warsim.frontline.api.redis.RedisHealth;
import com.warsim.frontline.api.redis.RedisMetricsSnapshot;
import com.warsim.frontline.api.redis.RedisService;
import com.warsim.frontline.api.redis.RedisState;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.Consumer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.RedisURI;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XAddArgs;
import io.lettuce.core.XAutoClaimArgs;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.protocol.ProtocolVersion;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class LettuceRedisService implements RedisService {
    private static final int MAX_DIRECTORY_NODES = 500;
    private static final String HEARTBEAT_SCRIPT = """
        local current = redis.call('HGET', KEYS[1], 'instanceId')
        local currentStarted = tonumber(redis.call('HGET', KEYS[1], 'startedAt') or '0')
        local incomingStarted = tonumber(ARGV[3])
        if current and current ~= ARGV[1] and currentStarted >= incomingStarted then
          return 0
        end
        for i = 4, #ARGV, 2 do redis.call('HSET', KEYS[1], ARGV[i], ARGV[i + 1]) end
        redis.call('PEXPIRE', KEYS[1], ARGV[2])
        redis.call('ZADD', KEYS[2], ARGV[25], ARGV[5])
        return 1
        """;
    private static final String PROCESSING_LOCK_SCRIPT = """
        if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
        if redis.call('SET', KEYS[2], '1', 'NX', 'PX', ARGV[1]) then return 1 end
        return 0
        """;

    private final RedisConfiguration configuration;
    private final String nodeId;
    private final UUID instanceId;
    private final Clock clock;
    private final ControlMessageCodec messageCodec = new ControlMessageCodec();
    private final MutableRedisMetrics metrics = new MutableRedisMetrics();
    private final AtomicReference<RedisState> state;
    private final AtomicReference<RedisHealth> health;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean consumerPolling = new AtomicBoolean();
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
    private final Map<ControlMessageType, ControlMessageHandler> handlers =
        new EnumMap<>(ControlMessageType.class);
    private final ScheduledExecutorService scheduler;
    private final PingRequestRegistry pingRequests;
    private volatile RedisClient client;
    private volatile StatefulRedisConnection<String, String> connection;
    private volatile RedisAsyncCommands<String, String> commands;
    private volatile CompletableFuture<Void> startFuture;

    public LettuceRedisService(
        RedisConfiguration configuration,
        String nodeId,
        UUID instanceId
    ) {
        this(configuration, nodeId, instanceId, Clock.systemUTC());
    }

    LettuceRedisService(
        RedisConfiguration configuration,
        String nodeId,
        UUID instanceId,
        Clock clock
    ) {
        this.configuration = configuration;
        this.nodeId = NodeIds.requireValid(nodeId);
        this.instanceId = instanceId;
        this.clock = clock;
        RedisState initial = configuration.enabled() ? RedisState.CREATED : RedisState.DISABLED;
        this.state = new AtomicReference<>(initial);
        this.health = new AtomicReference<>(RedisHealth.initial(initial));
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("warsim-redis-control").daemon(false).factory()
        );
        this.pingRequests = new PingRequestRegistry(scheduler);
        registerBuiltInHandlers();
    }

    @Override
    public boolean enabled() {
        return configuration.enabled();
    }

    @Override
    public String sanitizedAddress() {
        return RedisUriSanitizer.sanitize(configuration.uri());
    }

    @Override
    public String namespace() {
        return configuration.namespace();
    }

    @Override
    public RedisState state() {
        return state.get();
    }

    @Override
    public RedisHealth health() {
        return health.get();
    }

    @Override
    public RedisMetricsSnapshot metrics() {
        return metrics.snapshot(state.get(), connection != null && connection.isOpen());
    }

    @Override
    public synchronized CompletableFuture<Void> start() {
        if (!configuration.enabled()) {
            return CompletableFuture.completedFuture(null);
        }
        if (startFuture != null) {
            return startFuture;
        }
        try {
            RedisConfigurationValidator.validate(configuration);
        } catch (RuntimeException exception) {
            setHealth(RedisState.FAILED, false, "Redis配置无效");
            startFuture = CompletableFuture.failedFuture(exception);
            return startFuture;
        }
        state.set(RedisState.CONNECTING);
        RedisURI redisUri = buildRedisUri();
        client = RedisClient.create(redisUri);
        client.setOptions(ClientOptions.builder()
            .autoReconnect(true)
            .protocolVersion(ProtocolVersion.RESP3)
            .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
            .build());
        startFuture = client.connectAsync(io.lettuce.core.codec.StringCodec.UTF8, redisUri)
            .toCompletableFuture()
            .thenCompose(connected -> {
                connection = connected;
                commands = connected.async();
                return commands.ping().toCompletableFuture();
            })
            .thenCompose(pong -> {
                if (!"PONG".equalsIgnoreCase(pong)) {
                    return CompletableFuture.failedFuture(
                        new IllegalStateException("Redis PING returned " + pong)
                    );
                }
                setHealth(RedisState.HEALTHY, true, "Redis连接正常");
                if (configuration.streamsEnabled()) {
                    return ensureConsumerGroup().thenRun(this::startConsumer);
                }
                return CompletableFuture.completedFuture(null);
            })
            .exceptionally(failure -> {
                setHealth(
                    RedisState.UNAVAILABLE,
                    false,
                    RedisFailureClassifier.safeSummary(failure)
                );
                scheduleReconnect();
                throw new CompletionException(failure);
            });
        return startFuture;
    }

    @Override
    public CompletableFuture<Void> publishHeartbeat(NodeSnapshot snapshot) {
        StatefulRedisConnection<String, String> activeConnection = connection;
        if (!canAttemptHeartbeat(
            state.get(),
            activeConnection != null && activeConnection.isOpen(),
            closed.get()
        )) {
            metrics.failedHeartbeats.incrementAndGet();
            return CompletableFuture.failedFuture(new IllegalStateException("Redis is unavailable"));
        }
        Map<String, String> fields = NodeSnapshotMapper.toMap(snapshot);
        List<String> arguments = new ArrayList<>();
        arguments.add(snapshot.instanceId().toString());
        arguments.add(Long.toString(configuration.heartbeatTtlMillis()));
        arguments.add(Long.toString(snapshot.startedAt().toEpochMilli()));
        fields.forEach((key, value) -> {
            arguments.add(key);
            arguments.add(value);
        });
        String[] keys = {nodeKey(snapshot.nodeId()), lastSeenKey()};
        return this.<Long>future(commands.eval(
            HEARTBEAT_SCRIPT,
            ScriptOutputType.INTEGER,
            keys,
            arguments.toArray(String[]::new)
        )).thenAccept(result -> {
            if (result == null || result == 0) {
                throw new IllegalStateException("Stale node instance heartbeat rejected");
            }
            metrics.successfulHeartbeats.incrementAndGet();
            metrics.lastHeartbeat.set(snapshot.lastHeartbeatAt());
            setHealth(RedisState.HEALTHY, true, "Redis连接正常");
        }).exceptionally(failure -> {
            metrics.failedHeartbeats.incrementAndGet();
            degrade("Redis心跳写入失败");
            throw new java.util.concurrent.CompletionException(failure);
        });
    }

    @Override
    public CompletableFuture<Optional<NodeSnapshot>> findNode(String requestedNodeId) {
        NodeIds.requireValid(requestedNodeId);
        return activeNodeIds().thenCompose(ids -> {
            if (!ids.contains(requestedNodeId)) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            return future(commands.hgetall(nodeKey(requestedNodeId)))
                .thenApply(this::activeSnapshot);
        });
    }

    @Override
    public CompletableFuture<List<NodeSnapshot>> listActiveNodes() {
        return activeNodeIds().thenCompose(ids -> {
            List<CompletableFuture<Optional<NodeSnapshot>>> futures = ids.stream()
                .map(id -> future(commands.hgetall(nodeKey(id))).thenApply(this::activeSnapshot))
                .toList();
            return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> futures.stream()
                    .map(future -> future.getNow(Optional.empty()))
                    .flatMap(Optional::stream)
                    .limit(MAX_DIRECTORY_NODES)
                    .toList())
                .thenApply(nodes -> {
                    metrics.discoveredNodes.set(nodes.size());
                    return List.copyOf(nodes);
                });
        });
    }

    @Override
    public CompletableFuture<List<NodeSnapshot>> listActiveNodesByType(NodeType type) {
        return listActiveNodes().thenApply(nodes ->
            nodes.stream().filter(node -> node.nodeType() == type).toList()
        );
    }

    @Override
    public CompletableFuture<Boolean> isJoinable(String requestedNodeId) {
        return findNode(requestedNodeId).thenApply(snapshot ->
            snapshot.map(node -> node.isJoinable(
                Instant.now(clock), Duration.ofMillis(configuration.heartbeatTtlMillis())
            )).orElse(false)
        );
    }

    @Override
    public CompletableFuture<List<NodeSnapshot>> findJoinableNodes(NodeType type) {
        Instant now = Instant.now(clock);
        Duration ttl = Duration.ofMillis(configuration.heartbeatTtlMillis());
        return listActiveNodesByType(type).thenApply(nodes ->
            nodes.stream().filter(node -> node.isJoinable(now, ttl)).toList()
        );
    }

    @Override
    public CompletableFuture<Void> removeNode(String requestedNodeId) {
        NodeIds.requireValid(requestedNodeId);
        if (commands == null) {
            return CompletableFuture.completedFuture(null);
        }
        return future(commands.del(nodeKey(requestedNodeId)))
            .thenCombine(future(commands.zrem(lastSeenKey(), requestedNodeId)), (left, right) -> null);
    }

    @Override
    public CompletableFuture<List<NodeSnapshot>> refresh() {
        return listActiveNodes();
    }

    @Override
    public CompletableFuture<String> publish(ControlMessage message) {
        if (!healthy() || closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Redis message bus unavailable"));
        }
        String encoded = messageCodec.encode(message, configuration.maximumPayloadBytes());
        Map<String, String> body = Map.of("message", encoded);
        return future(commands.xadd(
            streamKey(message.targetNodeId()),
            new XAddArgs().maxlen(10_000).approximateTrimming(),
            body
        )).thenApply(id -> {
            metrics.published.incrementAndGet();
            return id;
        });
    }

    @Override
    public synchronized void registerHandler(
        ControlMessageType type,
        ControlMessageHandler handler
    ) {
        handlers.put(type, handler);
    }

    @Override
    public CompletableFuture<Duration> ping(String targetNodeId, Duration timeout) {
        NodeIds.requireValid(targetNodeId);
        UUID messageId = UUID.randomUUID();
        Instant createdAt = Instant.now(clock);
        CompletableFuture<Duration> result = pingRequests.register(messageId, createdAt, timeout);
        ControlMessage ping = new ControlMessage(
            1, messageId, ControlMessageType.NODE_PING, nodeId, targetNodeId, instanceId,
            createdAt, createdAt.plus(configuration.messageTtlMillis(), java.time.temporal.ChronoUnit.MILLIS),
            1, null, new byte[0]
        );
        publish(ping).whenComplete((id, failure) -> {
            if (failure != null) {
                pingRequests.fail(messageId, failure);
            }
        });
        return result;
    }

    private void registerBuiltInHandlers() {
        handlers.put(ControlMessageType.NODE_PING, message -> {
            Instant now = Instant.now(clock);
            ControlMessage pong = new ControlMessage(
                1, UUID.randomUUID(), ControlMessageType.NODE_PONG, nodeId, message.sourceNodeId(),
                instanceId, now, now.plusMillis(configuration.messageTtlMillis()), 1,
                message.messageId(), new byte[0]
            );
            return publish(pong).thenApply(id -> ControlMessageResult.ACKNOWLEDGED);
        });
        handlers.put(ControlMessageType.NODE_PONG, message -> {
            pingRequests.complete(message.correlationId(), Instant.now(clock));
            return CompletableFuture.completedFuture(ControlMessageResult.ACKNOWLEDGED);
        });
        handlers.put(ControlMessageType.NODE_REFRESH_REQUEST, message ->
            CompletableFuture.completedFuture(ControlMessageResult.ACKNOWLEDGED));
        handlers.put(ControlMessageType.NODE_REFRESH_RESPONSE, message ->
            CompletableFuture.completedFuture(ControlMessageResult.ACKNOWLEDGED));
    }

    private CompletableFuture<Void> ensureConsumerGroup() {
        return future(commands.xgroupCreate(
            XReadArgs.StreamOffset.from(streamKey(nodeId), "0-0"),
            groupName(),
            new XGroupCreateArgs().mkstream(true)
        )).handle((created, failure) -> {
            if (failure == null || isConsumerGroupAlreadyPresent(failure)) {
                return null;
            }
            throw new CompletionException(failure);
        });
    }

    private void startConsumer() {
        pollConsumer();
    }

    @SuppressWarnings("unchecked")
    private void pollConsumer() {
        if (closed.get() || !consumerPolling.compareAndSet(false, true) || commands == null) {
            return;
        }
        claimPending();
        Consumer<String> consumer = Consumer.from(groupName(), consumerName());
        commands.xreadgroup(
            consumer,
            new XReadArgs().block(configuration.streamBlockMillis()).count(configuration.streamBatchSize()),
            XReadArgs.StreamOffset.lastConsumed(streamKey(nodeId))
        ).whenComplete((messages, failure) -> {
            consumerPolling.set(false);
            if (closed.get()) {
                return;
            }
            if (failure != null) {
                degrade("Redis Stream读取失败");
                scheduler.schedule(this::pollConsumer, configuration.reconnectDelayMillis(), TimeUnit.MILLISECONDS);
                return;
            }
            processMessages(messages).whenComplete((ignored, processingFailure) -> pollConsumer());
        });
    }

    private void claimPending() {
        if (commands == null || closed.get()) {
            return;
        }
        XAutoClaimArgs<String> args = new XAutoClaimArgs<String>()
            .consumer(Consumer.from(groupName(), consumerName()))
            .minIdleTime(configuration.claimIdleMillis())
            .startId("0-0")
            .count(configuration.streamBatchSize());
        commands.xautoclaim(streamKey(nodeId), args).whenComplete((claimed, failure) -> {
            if (failure == null && claimed != null) {
                processMessages(claimed.getMessages());
            }
        });
    }

    private CompletableFuture<Void> processMessages(List<StreamMessage<String, String>> messages) {
        if (messages == null || messages.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        metrics.pending.set(messages.size());
        List<CompletableFuture<Void>> operations = messages.stream()
            .map(this::processMessage)
            .toList();
        return CompletableFuture.allOf(operations.toArray(CompletableFuture[]::new))
            .whenComplete((ignored, failure) -> metrics.pending.set(0));
    }

    private CompletableFuture<Void> processMessage(StreamMessage<String, String> streamMessage) {
        metrics.consumed.incrementAndGet();
        metrics.inFlight.incrementAndGet();
        ControlMessage message;
        try {
            message = messageCodec.decode(
                streamMessage.getBody().get("message"), nodeId, Instant.now(clock),
                configuration.maximumAttempts(), configuration.maximumPayloadBytes()
            );
        } catch (RuntimeException exception) {
            if (isExpiredMessageFailure(exception)) {
                metrics.expired.incrementAndGet();
            } else {
                metrics.invalid.incrementAndGet();
            }
            return acknowledge(streamMessage.getId()).whenComplete((ignored, failure) ->
                metrics.inFlight.decrementAndGet());
        }
        return acquireProcessingLock(message).thenCompose(acquired -> {
            if (!acquired) {
                metrics.duplicates.incrementAndGet();
                return acknowledge(streamMessage.getId());
            }
            ControlMessageHandler handler = handlers.get(message.messageType());
            if (handler == null) {
                metrics.invalid.incrementAndGet();
                return acknowledge(streamMessage.getId());
            }
            long remainingMillis = Math.max(
                1,
                Math.min(
                    configuration.messageTtlMillis(),
                    Duration.between(Instant.now(clock), message.expiresAt()).toMillis()
                )
            );
            return invokeHandler(handler, message, remainingMillis)
                .thenCompose(result -> handleResult(streamMessage.getId(), message, result));
        }).whenComplete((ignored, failure) -> metrics.inFlight.decrementAndGet());
    }

    private CompletableFuture<Void> handleResult(
        String streamId,
        ControlMessage message,
        ControlMessageResult result
    ) {
        if (result == ControlMessageResult.ACKNOWLEDGED) {
            return markProcessed(message).thenCompose(ignored -> acknowledge(streamId));
        }
        if (result == ControlMessageResult.RETRY && message.attempt() < configuration.maximumAttempts()) {
            metrics.retried.incrementAndGet();
            ControlMessage retry = new ControlMessage(
                message.protocolVersion(), message.messageId(), message.messageType(),
                message.sourceNodeId(), message.targetNodeId(), message.sourceInstanceId(),
                message.createdAt(), message.expiresAt(), message.attempt() + 1,
                message.correlationId(), message.payload()
            );
            return releaseProcessing(message)
                .thenCompose(ignored -> publish(retry))
                .thenCompose(ignored -> acknowledge(streamId));
        }
        metrics.deadLetter.incrementAndGet();
        Map<String, String> body = Map.of(
            "message", messageCodec.encode(message, configuration.maximumPayloadBytes())
        );
        return future(commands.xadd(
            deadLetterKey(nodeId),
            new XAddArgs().maxlen(10_000).approximateTrimming(),
            body
        )).thenCompose(ignored -> markProcessed(message))
            .thenCompose(ignored -> acknowledge(streamId));
    }

    private CompletableFuture<Boolean> acquireProcessingLock(ControlMessage message) {
        String[] keys = {
            dedupKey(message.targetNodeId(), message.messageId()),
            processingKey(message.targetNodeId(), message.messageId())
        };
        return this.<Long>future(commands.eval(
            PROCESSING_LOCK_SCRIPT,
            ScriptOutputType.INTEGER,
            keys,
            Long.toString(configuration.claimIdleMillis())
        )).thenApply(value -> value != null && value == 1);
    }

    private CompletableFuture<Void> markProcessed(ControlMessage message) {
        return future(commands.set(
            dedupKey(message.targetNodeId(), message.messageId()),
            "1",
            SetArgs.Builder.nx().ex(configuration.deduplicationTtlSeconds())
        )).thenCompose(ignored -> future(commands.del(
            processingKey(message.targetNodeId(), message.messageId())
        ))).thenApply(ignored -> null);
    }

    private CompletableFuture<Void> releaseProcessing(ControlMessage message) {
        return future(commands.del(
            processingKey(message.targetNodeId(), message.messageId())
        )).thenApply(ignored -> null);
    }

    private CompletableFuture<Void> acknowledge(String streamId) {
        return future(commands.xack(streamKey(nodeId), groupName(), streamId))
            .thenAccept(count -> metrics.acknowledged.addAndGet(count));
    }

    private CompletableFuture<List<String>> activeNodeIds() {
        if (!healthy()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Redis directory unavailable"));
        }
        double minimum = clock.millis() - configuration.heartbeatTtlMillis();
        return future(commands.zrangebyscore(
            lastSeenKey(),
            Range.from(Range.Boundary.including(minimum), Range.Boundary.unbounded()),
            Limit.create(0, MAX_DIRECTORY_NODES)
        ));
    }

    private Optional<NodeSnapshot> activeSnapshot(Map<String, String> fields) {
        if (fields == null || fields.isEmpty()) {
            return Optional.empty();
        }
        try {
            NodeSnapshot snapshot = NodeSnapshotMapper.fromMap(fields);
            if (!isDirectoryVisible(
                snapshot,
                Instant.now(clock),
                Duration.ofMillis(configuration.heartbeatTtlMillis())
            )) {
                return Optional.empty();
            }
            return Optional.of(snapshot);
        } catch (RuntimeException exception) {
            metrics.invalid.incrementAndGet();
            return Optional.empty();
        }
    }

    private void scheduleReconnect() {
        if (closed.get() || !reconnectScheduled.compareAndSet(false, true)) {
            return;
        }
        scheduler.schedule(() -> {
            reconnectScheduled.set(false);
            if (closed.get()) {
                return;
            }
            synchronized (this) {
                closeCurrentClient();
                startFuture = null;
            }
            metrics.reconnects.incrementAndGet();
            start().exceptionally(failure -> null);
        }, configuration.reconnectDelayMillis(), TimeUnit.MILLISECONDS);
    }

    private RedisURI buildRedisUri() {
        RedisURI uri = RedisURI.create(configuration.uri());
        uri.setDatabase(configuration.database());
        uri.setTimeout(Duration.ofMillis(configuration.connectionTimeoutMillis()));
        uri.setSsl(configuration.tlsEnabled() || "rediss".equalsIgnoreCase(uri.toURI().getScheme()));
        uri.setVerifyPeer(configuration.verifyHostname());
        if (!configuration.password().isBlank()) {
            if (configuration.username().isBlank()) {
                uri.setAuthentication(configuration.password().toCharArray());
            } else {
                uri.setAuthentication(configuration.username(), configuration.password().toCharArray());
            }
        }
        return uri;
    }

    private boolean healthy() {
        return state.get() == RedisState.HEALTHY
            && connection != null
            && connection.isOpen()
            && !closed.get();
    }

    static boolean canAttemptHeartbeat(
        RedisState currentState,
        boolean connectionOpen,
        boolean closed
    ) {
        return !closed
            && connectionOpen
            && currentState != RedisState.DISABLED
            && currentState != RedisState.CREATED
            && currentState != RedisState.CONNECTING
            && currentState != RedisState.FAILED
            && currentState != RedisState.STOPPING
            && currentState != RedisState.STOPPED;
    }

    static boolean isDirectoryVisible(
        NodeSnapshot snapshot,
        Instant now,
        Duration heartbeatTtl
    ) {
        return snapshot.lastHeartbeatAt().plus(heartbeatTtl).isAfter(now)
            && snapshot.lifecycleState() != ModuleState.FAILED
            && snapshot.lifecycleState() != ModuleState.STOPPING
            && snapshot.lifecycleState() != ModuleState.STOPPED
            && snapshot.availability() != NodeAvailability.STOPPING
            && snapshot.availability() != NodeAvailability.UNAVAILABLE;
    }

    static boolean isConsumerGroupAlreadyPresent(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains("BUSYGROUP")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    static CompletableFuture<ControlMessageResult> invokeHandler(
        ControlMessageHandler handler,
        ControlMessage message,
        long timeoutMillis
    ) {
        CompletableFuture<ControlMessageResult> handling;
        try {
            handling = handler.handle(message);
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(ControlMessageResult.RETRY);
        }
        if (handling == null) {
            return CompletableFuture.completedFuture(ControlMessageResult.RETRY);
        }
        return handling.orTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .handle((result, failure) ->
                failure == null && result != null ? result : ControlMessageResult.RETRY
            );
    }

    static boolean isExpiredMessageFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.toLowerCase(java.util.Locale.ROOT).contains("expired")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void degrade(String summary) {
        RedisState next = state.get() == RedisState.HEALTHY ? RedisState.DEGRADED : RedisState.UNAVAILABLE;
        setHealth(next, false, summary);
    }

    private void setHealth(RedisState next, boolean connected, String summary) {
        state.set(next);
        health.set(new RedisHealth(next, connected, Instant.now(clock), summary));
    }

    private String nodeKey(String id) {
        return configuration.namespace() + ":nodes:" + id;
    }

    private String lastSeenKey() {
        return configuration.namespace() + ":nodes:last_seen";
    }

    private String streamKey(String target) {
        return configuration.namespace() + ":streams:control:" + target;
    }

    private String deadLetterKey(String target) {
        return configuration.namespace() + ":streams:dead_letter:" + target;
    }

    private String dedupKey(String target, UUID messageId) {
        return configuration.namespace() + ":dedup:" + target + ":" + messageId;
    }

    private String processingKey(String target, UUID messageId) {
        return configuration.namespace() + ":processing:" + target + ":" + messageId;
    }

    private String groupName() {
        return "warsim-control";
    }

    private String consumerName() {
        return nodeId + "-" + instanceId;
    }

    private <T> CompletableFuture<T> future(RedisFuture<T> redisFuture) {
        return redisFuture.toCompletableFuture();
    }

    private void closeCurrentClient() {
        StatefulRedisConnection<String, String> currentConnection = connection;
        RedisClient currentClient = client;
        commands = null;
        connection = null;
        client = null;
        if (currentConnection != null) {
            currentConnection.closeAsync();
        }
        if (currentClient != null) {
            currentClient.shutdownAsync(
                100,
                configuration.connectionTimeoutMillis(),
                TimeUnit.MILLISECONDS
            );
        }
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        state.set(RedisState.STOPPING);
        pingRequests.close();
        RedisAsyncCommands<String, String> activeCommands = commands;
        if (activeCommands == null) {
            finishClose();
            return;
        }
        Instant now = Instant.now(clock);
        CompletableFuture<Void> cleanup = CompletableFuture.allOf(
            future(activeCommands.hset(
                nodeKey(nodeId),
                Map.of(
                    "lifecycleState", ModuleState.STOPPING.name(),
                    "availability", NodeAvailability.STOPPING.name(),
                    "acceptingPlayers", "false",
                    "lastHeartbeatAt", Long.toString(now.toEpochMilli())
                )
            )),
            future(activeCommands.del(nodeKey(nodeId))),
            future(activeCommands.zrem(lastSeenKey(), nodeId))
        ).orTimeout(configuration.connectionTimeoutMillis(), TimeUnit.MILLISECONDS);
        cleanup.whenComplete((ignored, failure) -> finishClose());
    }

    private void finishClose() {
        closeCurrentClient();
        scheduler.shutdownNow();
        state.set(configuration.enabled() ? RedisState.STOPPED : RedisState.DISABLED);
        health.set(new RedisHealth(state.get(), false, Instant.now(clock), "Redis服务已关闭"));
    }

}
