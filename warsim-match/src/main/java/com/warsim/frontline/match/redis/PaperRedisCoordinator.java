package com.warsim.frontline.match.redis;

import com.warsim.frontline.api.ModuleState;
import com.warsim.frontline.api.node.NodeDescriptor;
import com.warsim.frontline.api.redis.NodeAvailability;
import com.warsim.frontline.api.redis.NodeSnapshot;
import com.warsim.frontline.api.redis.RedisMetricsSnapshot;
import com.warsim.frontline.api.redis.RedisService;
import com.warsim.frontline.network.redis.LettuceRedisService;
import com.warsim.frontline.network.redis.RedisConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public final class PaperRedisCoordinator implements AutoCloseable {
    private static final long WARNING_INTERVAL_MILLIS = 30_000;

    private final JavaPlugin plugin;
    private final Logger logger;
    private final RedisConfiguration configuration;
    private final NodeDescriptor node;
    private final UUID instanceId = UUID.randomUUID();
    private final Instant startedAt = Instant.now();
    private final RedisService service;
    private final Supplier<PaperNodePublication> nodePublication;
    private final AtomicLong lastWarningAt = new AtomicLong();
    private final AtomicReference<com.warsim.frontline.api.redis.RedisState> lastObservedState;
    private int heartbeatTaskId = -1;

    public PaperRedisCoordinator(
        JavaPlugin plugin,
        RedisConfiguration configuration,
        NodeDescriptor node,
        Supplier<PaperNodePublication> nodePublication
    ) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.configuration = configuration;
        this.node = node;
        this.nodePublication = nodePublication;
        this.service = new LettuceRedisService(configuration, node.id(), instanceId);
        this.lastObservedState = new AtomicReference<>(service.state());
    }

    public void start() {
        service.start().whenComplete((ignored, failure) -> {
            if (failure != null) {
                warnLimited("Redis异步启动失败");
            } else {
                lastObservedState.set(service.state());
                logger.info("[warsim-redis] Redis生命周期 state=" + service.state()
                    + " address=" + service.sanitizedAddress());
            }
        });
        long ticks = Math.max(20, configuration.heartbeatIntervalMillis() / 50);
        heartbeatTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(
            plugin, this::sampleAndPublish, ticks, ticks
        );
    }

    private void sampleAndPublish() {
        if (!configuration.enabled()) {
            return;
        }
        int online = Bukkit.getOnlinePlayers().size();
        PaperNodePublication publication = nodePublication.get();
        int maximum = publication == null
            ? Math.min(100, Math.max(1, Bukkit.getMaxPlayers()))
            : Math.min(100, Math.max(1, publication.maximumPlayers()));
        NodeAvailability availability = publication == null
            ? NodeAvailability.AVAILABLE : publication.availability();
        boolean acceptingPlayers = publication == null || publication.acceptingPlayers();
        com.warsim.frontline.api.ModuleState lifecycleState = publication == null
            ? ModuleState.RUNNING : publication.lifecycleState();
        if (online >= maximum) {
            availability = NodeAvailability.FULL;
            acceptingPlayers = false;
        }
        NodeSnapshot snapshot = new NodeSnapshot(
            node.id(), node.type(), instanceId, lifecycleState, availability,
            Math.min(online, maximum), maximum, 0, acceptingPlayers, startedAt,
            Instant.now(), 1, plugin.getPluginMeta().getVersion()
        );
        service.publishHeartbeat(snapshot).whenComplete((ignored, failure) -> {
            if (failure != null) {
                lastObservedState.set(service.state());
                warnLimited("Redis节点心跳失败");
                return;
            }
            com.warsim.frontline.api.redis.RedisState previous =
                lastObservedState.getAndSet(service.state());
            if (previous != com.warsim.frontline.api.redis.RedisState.HEALTHY) {
                logger.info("[warsim-redis] Redis连接已恢复，节点心跳已重新注册。");
            }
        });
    }

    public void ping(CommandSender sender, String targetNodeId) {
        if (!service.enabled() || service.state() != com.warsim.frontline.api.redis.RedisState.HEALTHY) {
            sender.sendMessage("§cRedis当前不可用，无法执行节点PING。");
            return;
        }
        service.ping(targetNodeId, Duration.ofSeconds(5)).whenComplete((duration, failure) ->
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (failure != null) {
                    sender.sendMessage("§cRedis节点PING失败：" + safeReason(failure));
                } else {
                    sender.sendMessage("§a节点 " + targetNodeId + " 响应耗时 "
                        + duration.toMillis() + "ms。");
                }
            })
        );
    }

    public List<String> statusLines() {
        RedisMetricsSnapshot metrics = service.metrics();
        long heartbeatDelay = metrics.lastSuccessfulHeartbeat() == null
            ? -1 : Duration.between(metrics.lastSuccessfulHeartbeat(), Instant.now()).toMillis();
        boolean degradedValidation = service.state() != com.warsim.frontline.api.redis.RedisState.HEALTHY;
        return List.of(
            "§fRedis启用：§a" + service.enabled(),
            "§fRedis状态：§a" + service.state(),
            "§fRedis健康：§a" + service.health().summary(),
            "§fRedis地址：§a" + service.sanitizedAddress(),
            "§fRedis命名空间：§a" + service.namespace(),
            "§f节点实例ID：§a" + instanceId,
            "§f最近心跳：§a" + (metrics.lastSuccessfulHeartbeat() == null ? "尚未成功" : metrics.lastSuccessfulHeartbeat()),
            "§f心跳延迟：§a" + (heartbeatDelay < 0 ? "未知" : heartbeatDelay + "ms"),
            "§f活动节点数：§a" + metrics.discoveredNodes(),
            "§fStream消费者：§a" + (service.state() == com.warsim.frontline.api.redis.RedisState.HEALTHY),
            "§f消息 Pending/处理/重试/死信：§a" + metrics.pendingMessages() + "/"
                + metrics.acknowledgedMessages() + "/" + metrics.retriedMessages() + "/"
                + metrics.deadLetterMessages(),
            "§fRedis节点验证：§a" + service.enabled(),
            "§f降级验证：§a" + degradedValidation
        );
    }

    public RedisService service() {
        return service;
    }

    private void warnLimited(String message) {
        long now = System.currentTimeMillis();
        long previous = lastWarningAt.get();
        if (previous == 0 || now - previous >= WARNING_INTERVAL_MILLIS) {
            lastWarningAt.set(now);
            logger.warning("[warsim-redis] " + message + " state=" + service.state());
        }
    }

    private static String safeReason(Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null
            ? failure.getCause() : failure;
        return cause instanceof java.util.concurrent.TimeoutException
            ? "请求超时"
            : "目标离线或Redis通信异常";
    }

    @Override
    public void close() {
        if (heartbeatTaskId >= 0) {
            Bukkit.getScheduler().cancelTask(heartbeatTaskId);
        }
        service.close();
    }
}
