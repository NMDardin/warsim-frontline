package com.warsim.frontline.match.database;

import com.warsim.frontline.api.database.DatabaseErrorCode;
import com.warsim.frontline.api.database.DatabaseHealth;
import com.warsim.frontline.api.database.DatabaseMetricsSnapshot;
import com.warsim.frontline.api.database.DatabaseOperationException;
import com.warsim.frontline.api.database.DatabaseService;
import com.warsim.frontline.api.database.DatabaseState;
import com.warsim.frontline.database.PostgreSqlDatabaseService;
import com.warsim.frontline.database.SecretRedactor;
import com.warsim.frontline.database.config.DatabaseConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class PaperDatabaseCoordinator implements AutoCloseable {
    private static final long WARNING_INTERVAL_MILLIS = 30_000;

    private final JavaPlugin plugin;
    private final Logger logger;
    private final DatabaseConfiguration configuration;
    private final DatabaseService service;
    private final Clock clock;
    private final boolean debugLogging;
    private final AtomicLong lastWarningAt = new AtomicLong();
    private int healthTaskId = -1;

    public PaperDatabaseCoordinator(
        JavaPlugin plugin,
        DatabaseConfiguration configuration,
        boolean debugLogging
    ) {
        this(
            plugin,
            configuration,
            new PostgreSqlDatabaseService(configuration),
            Clock.systemUTC(),
            debugLogging
        );
    }

    PaperDatabaseCoordinator(
        JavaPlugin plugin,
        DatabaseConfiguration configuration,
        DatabaseService service,
        Clock clock,
        boolean debugLogging
    ) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.configuration = configuration;
        this.service = service;
        this.clock = clock;
        this.debugLogging = debugLogging;
    }

    public void start() {
        service.start().whenComplete((ignored, failure) -> {
            if (failure == null) {
                logger.info(
                    "[warsim-database] 数据库生命周期启动完成 state=" + service.state()
                        + " schema=" + service.schema()
                );
            } else {
                logFailure("数据库异步启动失败", failure, true);
            }
        });
        if (configuration.enabled()) {
            long ticks = configuration.healthCheckIntervalSeconds() * 20L;
            healthTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(
                plugin,
                this::triggerHealthCheck,
                ticks,
                ticks
            );
        }
    }

    public void playerJoined(UUID playerUuid, String currentName, Instant now) {
        if (service.state() != DatabaseState.HEALTHY) {
            return;
        }
        service.upsertOnJoin(playerUuid, currentName, now).whenComplete((profile, failure) -> {
            if (failure != null) {
                logFailure("玩家加入档案同步失败 playerUuid=" + playerUuid, failure, false);
            } else if (debugLogging) {
                logger.info("[warsim-database] 玩家档案已同步 playerUuid=" + playerUuid);
            }
        });
    }

    public void playerQuit(UUID playerUuid, String currentName, Instant now) {
        if (service.state() != DatabaseState.HEALTHY) {
            return;
        }
        service.updateLastSeen(playerUuid, currentName, now).whenComplete((profile, failure) -> {
            if (failure != null) {
                logFailure("玩家退出档案同步失败 playerUuid=" + playerUuid, failure, false);
            }
        });
    }

    public List<String> statusLines() {
        DatabaseHealth currentHealth = service.health();
        DatabaseMetricsSnapshot metrics = service.metrics();
        return List.of(
            "§f数据库启用：§a" + service.enabled(),
            "§f数据库状态：§a" + service.state(),
            "§f数据库健康：§a" + currentHealth.errorCode(),
            "§f数据库Schema：§a" + service.schema(),
            "§f连接池 活动/空闲/等待：§a"
                + metrics.activeConnections() + "/" + metrics.idleConnections()
                + "/" + metrics.waitingThreads(),
            "§f数据库任务 活动/队列/完成：§a"
                + metrics.activeTasks() + "/" + metrics.queuedTasks()
                + "/" + metrics.completedTasks(),
            "§f最近健康检查：§a"
                + (currentHealth.checkedAt() == null ? "尚未执行" : currentHealth.checkedAt())
        );
    }

    private void triggerHealthCheck() {
        service.healthCheck().whenComplete((health, failure) -> {
            if (failure != null) {
                logFailure("数据库健康检查失败", failure, false);
            } else if (health.state() == DatabaseState.HEALTHY) {
                lastWarningAt.set(0);
            }
        });
    }

    private void logFailure(String context, Throwable failure, boolean includeStackTrace) {
        Throwable cause = unwrap(failure);
        DatabaseErrorCode code = cause instanceof DatabaseOperationException databaseFailure
            ? databaseFailure.code()
            : DatabaseErrorCode.UNKNOWN;
        long now = clock.millis();
        long previous = lastWarningAt.get();
        if (!includeStackTrace
            && previous != 0
            && now - previous < WARNING_INTERVAL_MILLIS) {
            return;
        }
        lastWarningAt.set(now);
        String message = "[warsim-database] " + context + " errorCode=" + code;
        if (includeStackTrace || code == DatabaseErrorCode.UNKNOWN) {
            logger.log(Level.SEVERE, message, cause);
        } else {
            logger.warning(message + " reason=" + safeMessage(cause));
        }
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null
            ? failure.getClass().getSimpleName()
            : SecretRedactor.redact(message);
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof CompletionException && failure.getCause() != null
            ? failure.getCause()
            : failure;
    }

    @Override
    public void close() {
        if (healthTaskId >= 0) {
            Bukkit.getScheduler().cancelTask(healthTaskId);
            healthTaskId = -1;
        }
        service.close();
    }
}
