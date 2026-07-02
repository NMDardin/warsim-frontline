package com.warsim.frontline.velocity;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.warsim.frontline.api.ModuleState;
import com.warsim.frontline.api.node.NodeType;
import com.warsim.frontline.api.redis.NodeAvailability;
import com.warsim.frontline.api.redis.NodeSnapshot;
import com.warsim.frontline.api.redis.RedisService;
import com.warsim.frontline.network.redis.LettuceRedisService;
import com.warsim.frontline.network.redis.RedisConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;

final class VelocityRedisCoordinator implements AutoCloseable {
    private static final long WARNING_INTERVAL_MILLIS = 30_000;

    private final ProxyServer proxy;
    private final Object plugin;
    private final Logger logger;
    private final RedisConfiguration configuration;
    private final String nodeId;
    private final UUID instanceId = UUID.randomUUID();
    private final Instant startedAt = Instant.now();
    private final RedisService service;
    private final AtomicLong lastWarningAt = new AtomicLong();
    private final AtomicReference<com.warsim.frontline.api.redis.RedisState> lastObservedState;
    private ScheduledTask heartbeatTask;

    VelocityRedisCoordinator(
        ProxyServer proxy,
        Object plugin,
        Logger logger,
        RedisConfiguration configuration,
        String nodeId
    ) {
        this.proxy = proxy;
        this.plugin = plugin;
        this.logger = logger;
        this.configuration = configuration;
        this.nodeId = nodeId;
        this.service = new LettuceRedisService(configuration, nodeId, instanceId);
        this.lastObservedState = new AtomicReference<>(service.state());
    }

    void start() {
        service.start().whenComplete((ignored, failure) -> {
            if (failure != null) {
                logger.warn("[warsim-redis] Redis启动失败，代理继续使用降级转服验证。");
            } else {
                lastObservedState.set(service.state());
                logger.info("[warsim-redis] Redis生命周期 state={} address={}",
                    service.state(), service.sanitizedAddress());
            }
        });
        heartbeatTask = proxy.getScheduler().buildTask(plugin, this::publishHeartbeat)
            .repeat(Duration.ofMillis(configuration.heartbeatIntervalMillis()))
            .schedule();
    }

    private void publishHeartbeat() {
        if (!configuration.enabled()) {
            return;
        }
        int online = proxy.getPlayerCount();
        NodeSnapshot snapshot = new NodeSnapshot(
            nodeId, NodeType.PROXY, instanceId, ModuleState.RUNNING,
            NodeAvailability.AVAILABLE, online, Math.max(online + 1, 10000), 0,
            false, startedAt, Instant.now(), 1, "0.2.0-SNAPSHOT"
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
                logger.info("[warsim-redis] Redis连接已恢复，代理心跳已重新注册。");
            }
        });
    }

    RedisService service() {
        return service;
    }

    boolean degradedValidation() {
        return service.state() != com.warsim.frontline.api.redis.RedisState.HEALTHY;
    }

    void warnDegradedValidation() {
        warnLimited("Redis不可用，转服使用T-002注册服务器降级验证");
    }

    private void warnLimited(String message) {
        long now = System.currentTimeMillis();
        long previous = lastWarningAt.get();
        if (previous == 0 || now - previous >= WARNING_INTERVAL_MILLIS) {
            lastWarningAt.set(now);
            logger.warn("[warsim-redis] {} state={}", message, service.state());
        }
    }

    @Override
    public void close() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
        }
        service.close();
    }
}
