package com.warsim.frontline.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.warsim.frontline.network.MessageCodec;
import com.warsim.frontline.network.NetworkMessage;
import com.warsim.frontline.network.ProtocolException;
import com.warsim.frontline.network.ProtocolVersion;
import com.warsim.frontline.network.RejectionCode;
import com.warsim.frontline.network.TransferAccepted;
import com.warsim.frontline.network.TransferRejected;
import com.warsim.frontline.network.TransferRequest;
import com.warsim.frontline.api.redis.NodeAvailability;
import com.warsim.frontline.api.redis.NodeSnapshot;
import com.warsim.frontline.api.redis.RedisState;
import com.warsim.frontline.network.redis.TransferTargetPolicy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

@Plugin(
    id = "warsim-frontline",
    name = "WarSim Frontline",
    version = "0.2.0-SNAPSHOT",
    description = "WarSim Frontline Velocity transfer bridge"
)
public final class WarSimVelocityPlugin implements NetworkBridge {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final MessageCodec codec = new MessageCodec();
    private final Map<UUID, UUID> activeRequests = new ConcurrentHashMap<>();
    private VelocityNetworkConfig config;
    private MinecraftChannelIdentifier channel;
    private VelocityRedisCoordinator redisCoordinator;
    private final TransferTargetPolicy transferTargetPolicy = new TransferTargetPolicy();

    @Inject
    public WarSimVelocityPlugin(
        ProxyServer proxy,
        Logger logger,
        @DataDirectory Path dataDirectory
    ) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public EventTask onProxyInitialize(ProxyInitializeEvent event) {
        return EventTask.async(() -> {
            config = VelocityNetworkConfig.load(dataDirectory, logger);
            channel = MinecraftChannelIdentifier.from(config.channel());
            proxy.getChannelRegistrar().register(channel);
            redisCoordinator = new VelocityRedisCoordinator(
                proxy, this, logger, config.redis(), config.proxyNodeId()
            );
            redisCoordinator.start();
            logger.info(
                "[warsim-velocity] 启动完成 channel={} sources={} targets={}",
                config.channel(),
                config.allowedSources(),
                config.allowedTargets()
            );
        });
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (channel == null || !channel.equals(event.getIdentifier())) {
            return;
        }
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection backend)) {
            logger.warn("[warsim-network] result=REJECTED rejectionCode=INVALID_SOURCE source=client-or-unknown");
            return;
        }
        Player player = backend.getPlayer();
        if (event.getTarget() != player) {
            logger.warn("[warsim-network] result=REJECTED rejectionCode=INVALID_SOURCE reason=target-mismatch");
            return;
        }

        NetworkMessage decoded;
        try {
            decoded = codec.decode(
                event.getData(),
                new MessageCodec.DecodePolicy(
                    config.maximumMessageBytes(),
                    config.requestTimeoutMillis(),
                    1000,
                    Clock.systemUTC()
                )
            );
        } catch (ProtocolException exception) {
            logger.warn("[warsim-network] result=REJECTED rejectionCode=INVALID_PAYLOAD reason={}", exception.getMessage());
            return;
        }
        if (!(decoded instanceof TransferRequest request)) {
            logger.warn(
                "[warsim-network] requestId={} result=REJECTED rejectionCode=INVALID_PAYLOAD reason=unexpected-type",
                decoded.requestId()
            );
            return;
        }
        handleTransfer(backend, player, request);
    }

    private void handleTransfer(ServerConnection backend, Player player, TransferRequest request) {
        String actualSource = backend.getServerInfo().getName();
        if (!request.playerUuid().equals(player.getUniqueId())) {
            reject(backend, request, RejectionCode.PLAYER_NOT_FOUND, "玩家身份验证失败。");
            return;
        }
        Optional<ServerConnection> current = player.getCurrentServer();
        if (current.isEmpty() || current.get() != backend || !actualSource.equals(request.sourceNodeId())) {
            reject(backend, request, RejectionCode.INVALID_SOURCE, "转服请求来源无效。");
            return;
        }
        if (!config.allowedSources().contains(actualSource)) {
            reject(backend, request, RejectionCode.INVALID_SOURCE, "当前节点不允许发起转服。");
            return;
        }
        if (!player.hasPermission("warsim.player.join")) {
            reject(backend, request, RejectionCode.PERMISSION_DENIED, "你没有加入战场的权限。");
            return;
        }
        if (!config.allowedTargets().contains(request.targetNodeId())) {
            reject(backend, request, RejectionCode.TARGET_NOT_FOUND, "目标战场不存在或不允许加入。");
            return;
        }
        if (actualSource.equals(request.targetNodeId())) {
            reject(backend, request, RejectionCode.ALREADY_CONNECTED, "你已连接到该节点。");
            return;
        }
        Optional<RegisteredServer> target = proxy.getServer(request.targetNodeId());
        if (target.isEmpty()) {
            reject(backend, request, RejectionCode.TARGET_NOT_FOUND, "目标战场未在代理注册。");
            return;
        }
        if (redisCoordinator != null
            && redisCoordinator.service().state() == RedisState.HEALTHY) {
            redisCoordinator.service().findNode(request.targetNodeId()).whenComplete((snapshot, failure) ->
                proxy.getScheduler().buildTask(this, () -> {
                    if (failure != null) {
                        logger.warn("[warsim-redis] 节点目录查询失败，使用T-002降级验证。");
                        proceedTransfer(backend, player, request, target.get());
                        return;
                    }
                    TransferTargetPolicy.Decision decision = transferTargetPolicy.assess(
                        redisCoordinator.service().state(),
                        snapshot,
                        java.time.Instant.now(),
                        Duration.ofMillis(config.redis().heartbeatTtlMillis())
                    );
                    switch (decision) {
                        case ALLOW, FALLBACK -> proceedTransfer(backend, player, request, target.get());
                        case OFFLINE -> reject(
                            backend, request, RejectionCode.TARGET_OFFLINE, "目标战场当前离线。"
                        );
                        case FULL -> reject(
                            backend, request, RejectionCode.TARGET_FULL, "目标战场人数已满。"
                        );
                        case DRAINING -> reject(
                            backend, request, RejectionCode.TARGET_DRAINING, "目标战场正在停止接收玩家。"
                        );
                        case UNKNOWN -> reject(
                            backend, request, RejectionCode.TARGET_STATE_UNKNOWN, "目标战场状态暂不可用。"
                        );
                    }
                }).schedule()
            );
            return;
        }
        if (redisCoordinator != null && redisCoordinator.service().enabled()) {
            redisCoordinator.warnDegradedValidation();
        }
        proceedTransfer(backend, player, request, target.get());
    }

    private void proceedTransfer(
        ServerConnection backend,
        Player player,
        TransferRequest request,
        RegisteredServer target
    ) {
        UUID existing = activeRequests.putIfAbsent(player.getUniqueId(), request.requestId());
        if (existing != null) {
            reject(backend, request, RejectionCode.ALREADY_CONNECTED, "你已有一个正在处理的转服请求。");
            return;
        }
        proxy.getScheduler()
            .buildTask(
                this,
                () -> activeRequests.remove(player.getUniqueId(), request.requestId())
            )
            .delay(Duration.ofMillis(config.requestTimeoutMillis()))
            .schedule();

        TransferAccepted accepted = new TransferAccepted(
            ProtocolVersion.CURRENT,
            request.requestId(),
            request.playerUuid(),
            request.sourceNodeId(),
            request.targetNodeId(),
            Clock.systemUTC().millis()
        );
        send(backend, accepted);
        log(request, "ACCEPTED", null);

        player.createConnectionRequest(target).connect().whenComplete((result, failure) -> {
            activeRequests.remove(player.getUniqueId(), request.requestId());
            if (failure != null) {
                player.sendMessage(Component.text("连接目标战场时发生内部错误，请稍后重试。"));
                sendFailureIfStillOnSource(
                    backend, player, request, RejectionCode.INTERNAL_ERROR, "连接目标战场时发生内部错误。"
                );
                logger.error(
                    transferLog(request, "FAILED", RejectionCode.INTERNAL_ERROR.name()),
                    failure
                );
                return;
            }
            if (!result.isSuccessful()) {
                RejectionCode code = mapFailure(result);
                String message = failureMessage(result);
                player.sendMessage(Component.text(message));
                sendFailureIfStillOnSource(backend, player, request, code, message);
                log(request, "FAILED", code.name());
                return;
            }
            log(request, "CONNECTED", null);
        });
    }

    private void sendFailureIfStillOnSource(
        ServerConnection backend,
        Player player,
        TransferRequest request,
        RejectionCode code,
        String message
    ) {
        if (player.getCurrentServer().filter(connection -> connection == backend).isPresent()) {
            reject(backend, request, code, message);
        }
    }

    private static RejectionCode mapFailure(ConnectionRequestBuilder.Result result) {
        return switch (result.getStatus()) {
            case ALREADY_CONNECTED -> RejectionCode.ALREADY_CONNECTED;
            case SERVER_DISCONNECTED, CONNECTION_CANCELLED, CONNECTION_IN_PROGRESS ->
                RejectionCode.TARGET_UNAVAILABLE;
            default -> RejectionCode.INTERNAL_ERROR;
        };
    }

    private static String failureMessage(ConnectionRequestBuilder.Result result) {
        return switch (result.getStatus()) {
            case ALREADY_CONNECTED -> "你已连接到目标战场。";
            case CONNECTION_IN_PROGRESS -> "已有连接请求正在处理中。";
            case CONNECTION_CANCELLED -> "连接目标战场的请求已被取消。";
            case SERVER_DISCONNECTED -> "目标战场当前不可用。";
            default -> "无法连接目标战场，请稍后重试。";
        };
    }

    private void reject(
        ServerConnection backend,
        TransferRequest request,
        RejectionCode code,
        String userMessage
    ) {
        TransferRejected rejected = new TransferRejected(
            ProtocolVersion.CURRENT,
            request.requestId(),
            request.playerUuid(),
            request.sourceNodeId(),
            request.targetNodeId(),
            Clock.systemUTC().millis(),
            code,
            userMessage
        );
        send(backend, rejected);
        log(request, "REJECTED", code.name());
    }

    @Override
    public boolean send(ServerConnection connection, NetworkMessage message) {
        try {
            byte[] payload = codec.encode(message, config.maximumMessageBytes());
            return connection.sendPluginMessage(channel, payload);
        } catch (ProtocolException | RuntimeException exception) {
            logger.error("[warsim-network] 代理响应编码或发送失败。", exception);
            return false;
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        activeRequests.remove(event.getPlayer().getUniqueId());
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (channel != null) {
            proxy.getChannelRegistrar().unregister(channel);
        }
        if (redisCoordinator != null) {
            redisCoordinator.close();
        }
        activeRequests.clear();
        logger.info("[warsim-velocity] WarSim: Frontline 已安全关闭。");
    }

    private void log(NetworkMessage message, String result, String rejectionCode) {
        logger.info(transferLog(message, result, rejectionCode));
    }

    private static String transferLog(NetworkMessage message, String result, String rejectionCode) {
        return "[warsim-network] module=velocity requestId=" + message.requestId()
            + " playerUuid=" + message.playerUuid()
            + " sourceNode=" + message.sourceNodeId()
            + " targetNode=" + message.targetNodeId()
            + " result=" + result
            + " rejectionCode=" + (rejectionCode == null ? "NONE" : rejectionCode);
    }
}
