package com.warsim.frontline.match.reset;

import com.warsim.frontline.api.match.MatchResetContext;
import com.warsim.frontline.api.match.MatchResetResult;
import com.warsim.frontline.api.match.MatchResetService;
import com.warsim.frontline.api.match.MatchSnapshot;
import com.warsim.frontline.api.match.MatchState;
import com.warsim.frontline.match.config.ResetHoldingSpawn;
import com.warsim.frontline.match.config.RoundResetPaperConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.util.Vector;

public final class PaperMatchResetService implements MatchResetService, AutoCloseable {
    private final JavaPlugin plugin;
    private final RoundResetPaperConfiguration configuration;
    private final Predicate<UUID> localSessionActive;
    private final Supplier<MatchSnapshot> matchSnapshotSupplier;
    private boolean closed;
    private ResetKey activeKey;
    private CompletableFuture<MatchResetResult> activeFuture;
    private ResetKey lastCompletedKey;
    private MatchResetResult lastCompletedResult;
    private int scheduledTaskId = -1;
    private long attempts;
    private long successes;
    private long failures;
    private long duplicateRequests;
    private long concurrentRequestRejections;
    private long playersTargeted;
    private long playersEvacuated;
    private long playerEvacuationFailures;
    private long worldsScanned;
    private long entitiesFound;
    private long entitiesRemoved;
    private long entityRemovalFailures;
    private Instant lastStartedAt;
    private Instant lastCompletedAt;
    private long lastDurationNanos;
    private UUID lastMatchId;
    private long lastLifecycleRevision;
    private String lastResult = "NONE";
    private String lastErrorSummary = "none";
    private long lastTargetedPlayers;
    private long lastEvacuatedPlayers;
    private long lastRemovedEntities;

    public PaperMatchResetService(
        JavaPlugin plugin,
        RoundResetPaperConfiguration configuration,
        Predicate<UUID> localSessionActive,
        Supplier<MatchSnapshot> matchSnapshotSupplier
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.localSessionActive = Objects.requireNonNull(localSessionActive, "localSessionActive");
        this.matchSnapshotSupplier = Objects.requireNonNull(matchSnapshotSupplier, "matchSnapshotSupplier");
    }

    @Override
    public synchronized CompletableFuture<MatchResetResult> reset(MatchResetContext context) {
        Objects.requireNonNull(context, "context");
        ResetKey key = new ResetKey(context.matchId(), context.lifecycleRevision());
        if (closed) return CompletableFuture.completedFuture(
            completedFailure(key, "Round Reset service is closed"));
        if (!configuration.enabled()) return CompletableFuture.completedFuture(
            completedFailure(key, "Round Reset is disabled"));
        if (key.equals(activeKey) && activeFuture != null) {
            duplicateRequests++;
            return activeFuture;
        }
        if (key.equals(lastCompletedKey) && lastCompletedResult != null) {
            duplicateRequests++;
            return CompletableFuture.completedFuture(lastCompletedResult);
        }
        if (activeFuture != null) {
            concurrentRequestRejections++;
            return CompletableFuture.completedFuture(
                completedFailure(key, "Another Round Reset transaction is already active"));
        }
        attempts++;
        lastStartedAt = Instant.now();
        lastMatchId = key.matchId();
        lastLifecycleRevision = key.lifecycleRevision();
        lastResult = "ACTIVE";
        lastErrorSummary = "none";
        CompletableFuture<MatchResetResult> future = new CompletableFuture<>();
        activeKey = key;
        activeFuture = future;
        try {
            scheduledTaskId = Bukkit.getScheduler().scheduleSyncDelayedTask(
                plugin,
                () -> runScheduled(key, future),
                configuration.startDelayTicks()
            );
            if (scheduledTaskId < 0) {
                finishActive(key, future, MatchResetResult.failure("Round Reset task scheduling failed"));
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "[warsim-reset] Failed to schedule Round Reset", exception);
            finishActive(key, future, MatchResetResult.failure("Round Reset task scheduling failed"));
        }
        return future;
    }

    public boolean cancelIfActiveContextInvalid(String reason) {
        ResetKey key;
        synchronized (this) {
            if (activeKey == null || activeFuture == null) return false;
            key = activeKey;
        }
        String failure = resetContextFailure(key);
        if (failure == null) return false;
        return cancelIfActive(key.matchId(), key.lifecycleRevision(), reason + ": " + failure);
    }

    public synchronized boolean cancelIfActive(UUID matchId, long lifecycleRevision, String reason) {
        ResetKey key = new ResetKey(matchId, lifecycleRevision);
        if (!key.equals(activeKey) || activeFuture == null) return false;
        CompletableFuture<MatchResetResult> future = activeFuture;
        cancelScheduledTaskLocked();
        finishActive(key, future, MatchResetResult.failure(sanitize(reason)));
        return true;
    }

    public synchronized List<String> statusLines() {
        return List.of(
            "§6WarSim Round Reset",
            "§f启用：§a" + configuration.enabled(),
            "§f活动事务：§a" + (activeFuture != null),
            "§f最近Match：§a" + (lastMatchId == null ? "无" : lastMatchId),
            "§f最近Revision：§a" + lastLifecycleRevision,
            "§f最近结果：§a" + lastResult,
            "§f最近耗时：§a" + Duration.ofNanos(lastDurationNanos).toMillis() + "ms",
            "§f累计尝试/成功/失败：§a" + attempts + "/" + successes + "/" + failures,
            "§f最近撤离玩家：§a" + lastEvacuatedPlayers + "/" + lastTargetedPlayers,
            "§f最近删除瞬时实体：§a" + lastRemovedEntities,
            "§f最近错误：§a" + lastErrorSummary
        );
    }

    public synchronized boolean enabled() {
        return configuration.enabled();
    }

    private void runScheduled(ResetKey key, CompletableFuture<MatchResetResult> future) {
        synchronized (this) {
            scheduledTaskId = -1;
            if (closed || !plugin.isEnabled() || !key.equals(activeKey) || future != activeFuture) {
                finishActive(key, future, MatchResetResult.failure("Round Reset transaction is no longer active"));
                return;
            }
        }
        String contextFailure = resetContextFailure(key);
        if (contextFailure != null) {
            plugin.getLogger().warning("[warsim-reset] " + contextFailure);
            finishActive(key, future, MatchResetResult.failure(contextFailure));
            return;
        }
        ResetReport report = new ResetReport();
        try {
            if (configuration.evacuateOnlinePlayers()) {
                evacuatePlayers(report);
            }
            collectAndRemoveTransientEntities(report);
        } catch (RuntimeException exception) {
            report.fail("Round Reset failed: " + sanitize(exception.getMessage()));
            plugin.getLogger().log(Level.WARNING, "[warsim-reset] Round Reset threw", exception);
        }
        MatchResetResult result = report.successful()
            ? new MatchResetResult(true, successSummary(key, report))
            : MatchResetResult.failure(report.summary());
        recordReport(report);
        finishActive(key, future, result);
    }

    private void evacuatePlayers(ResetReport report) {
        Location holding = holdingLocation(report);
        if (holding == null) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!localSessionActive.test(player.getUniqueId())) continue;
            report.targetedPlayers++;
            try {
                player.closeInventory();
                if (player.isInsideVehicle()) player.leaveVehicle();
                player.setGameMode(GameMode.SPECTATOR);
                player.setFireTicks(0);
                player.setFallDistance(0);
                player.setVelocity(new Vector(0, 0, 0));
                for (PotionEffect effect : List.copyOf(player.getActivePotionEffects())) {
                    player.removePotionEffect(effect.getType());
                }
                if (!player.teleport(holding)) {
                    report.evacuationFailures++;
                    report.fail("Player evacuation failed");
                    plugin.getLogger().warning("[warsim-reset] Player evacuation returned false playerUuid="
                        + player.getUniqueId());
                } else {
                    report.evacuatedPlayers++;
                }
            } catch (RuntimeException exception) {
                report.evacuationFailures++;
                report.fail("Player evacuation failed");
                plugin.getLogger().log(
                    Level.WARNING,
                    "[warsim-reset] Player evacuation threw playerUuid=" + player.getUniqueId(),
                    exception
                );
            }
        }
    }

    private Location holdingLocation(ResetReport report) {
        ResetHoldingSpawn spawn = configuration.holdingSpawn();
        if (spawn == null) {
            report.fail("Holding spawn is not configured");
            return null;
        }
        World world = Bukkit.getWorld(spawn.world());
        if (world == null) {
            report.fail("Holding spawn world is not loaded");
            return null;
        }
        int chunkX = ((int) Math.floor(spawn.x())) >> 4;
        int chunkZ = ((int) Math.floor(spawn.z())) >> 4;
        if (!world.isChunkLoaded(chunkX, chunkZ) && !world.loadChunk(chunkX, chunkZ, false)) {
            report.fail("Holding spawn chunk is not loaded");
            return null;
        }
        return new Location(world, spawn.x(), spawn.y(), spawn.z(), spawn.yaw(), spawn.pitch());
    }

    private void collectAndRemoveTransientEntities(ResetReport report) {
        List<Entity> candidates = new ArrayList<>();
        for (String worldName : configuration.transientWorlds()) {
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                report.fail("Configured transient world is not loaded: " + worldName);
                continue;
            }
            report.worldsScanned++;
            for (Entity entity : world.getEntities()) {
                if (transientEntity(entity)) candidates.add(entity);
            }
        }
        report.entitiesFound = candidates.size();
        if (candidates.size() > configuration.maximumTransientEntities()) {
            report.fail("Transient entity limit exceeded: " + candidates.size()
                + "/" + configuration.maximumTransientEntities());
            return;
        }
        for (Entity entity : candidates) {
            try {
                entity.remove();
                report.entitiesRemoved++;
            } catch (RuntimeException exception) {
                report.entityRemovalFailures++;
                report.fail("Transient entity removal failed");
                plugin.getLogger().log(
                    Level.WARNING,
                    "[warsim-reset] Entity removal threw type="
                        + entity.getType() + " uuid=" + entity.getUniqueId(),
                    exception
                );
            }
        }
    }

    private static boolean transientEntity(Entity entity) {
        return entity instanceof Item
            || entity instanceof ExperienceOrb
            || entity instanceof Projectile
            || entity instanceof AreaEffectCloud
            || entity instanceof TNTPrimed
            || entity instanceof FallingBlock
            || entity instanceof Firework;
    }

    private synchronized void finishActive(
        ResetKey key,
        CompletableFuture<MatchResetResult> future,
        MatchResetResult result
    ) {
        if (!key.equals(activeKey) || future != activeFuture) {
            if (!future.isDone()) future.complete(result);
            return;
        }
        if (result.successful()) successes++;
        else failures++;
        lastCompletedAt = Instant.now();
        lastDurationNanos = lastStartedAt == null ? 0 : Duration.between(lastStartedAt, lastCompletedAt).toNanos();
        lastCompletedKey = key;
        lastCompletedResult = result;
        lastResult = result.successful() ? "SUCCESS" : "FAILURE";
        lastErrorSummary = result.successful() ? "none" : sanitize(result.summary());
        activeKey = null;
        activeFuture = null;
        scheduledTaskId = -1;
        if (!future.isDone()) future.complete(result);
    }

    private synchronized void recordReport(ResetReport report) {
        playersTargeted += report.targetedPlayers;
        playersEvacuated += report.evacuatedPlayers;
        playerEvacuationFailures += report.evacuationFailures;
        worldsScanned += report.worldsScanned;
        entitiesFound += report.entitiesFound;
        entitiesRemoved += report.entitiesRemoved;
        entityRemovalFailures += report.entityRemovalFailures;
        lastTargetedPlayers = report.targetedPlayers;
        lastEvacuatedPlayers = report.evacuatedPlayers;
        lastRemovedEntities = report.entitiesRemoved;
    }

    private synchronized MatchResetResult completedFailure(ResetKey key, String summary) {
        failures++;
        lastMatchId = key.matchId();
        lastLifecycleRevision = key.lifecycleRevision();
        lastCompletedAt = Instant.now();
        lastResult = "FAILURE";
        lastErrorSummary = sanitize(summary);
        return MatchResetResult.failure(lastErrorSummary);
    }

    private String resetContextFailure(ResetKey key) {
        MatchSnapshot snapshot;
        try {
            snapshot = matchSnapshotSupplier.get();
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "[warsim-reset] Round Reset context validation threw", exception);
            return "Round Reset context validation failed matchId=" + key.matchId()
                + " revision=" + key.lifecycleRevision();
        }
        if (snapshot == null) {
            return "Round Reset context unavailable matchId=" + key.matchId()
                + " revision=" + key.lifecycleRevision();
        }
        if (!Objects.equals(snapshot.matchId(), key.matchId())) {
            return "Round Reset context stale matchId=" + key.matchId()
                + " revision=" + key.lifecycleRevision()
                + " currentMatchId=" + snapshot.matchId();
        }
        if (snapshot.lifecycleRevision() != key.lifecycleRevision()) {
            return "Round Reset context stale matchId=" + key.matchId()
                + " revision=" + key.lifecycleRevision()
                + " currentRevision=" + snapshot.lifecycleRevision();
        }
        if (snapshot.state() != MatchState.RESETTING) {
            return "Round Reset context stale matchId=" + key.matchId()
                + " revision=" + key.lifecycleRevision()
                + " currentState=" + snapshot.state();
        }
        return null;
    }

    private static String successSummary(ResetKey key, ResetReport report) {
        return "Round Reset complete matchId=" + key.matchId()
            + " revision=" + key.lifecycleRevision()
            + " evacuatedPlayers=" + report.evacuatedPlayers
            + " entitiesRemoved=" + report.entitiesRemoved;
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) return "Round Reset failed";
        String cleaned = value.replaceAll("\\p{Cc}", "").trim();
        return cleaned.length() <= 128 ? cleaned : cleaned.substring(0, 128);
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        cancelScheduledTaskLocked();
        if (activeFuture != null && activeKey != null) {
            finishActive(activeKey, activeFuture,
                MatchResetResult.failure("Plugin is shutting down; Round Reset was cancelled"));
        }
    }

    private void cancelScheduledTaskLocked() {
        if (scheduledTaskId >= 0) {
            Bukkit.getScheduler().cancelTask(scheduledTaskId);
            scheduledTaskId = -1;
        }
    }

    private record ResetKey(UUID matchId, long lifecycleRevision) {}

    private final class ResetReport {
        private boolean successful = true;
        private String summary = "Round Reset complete";
        private long targetedPlayers;
        private long evacuatedPlayers;
        private long evacuationFailures;
        private long worldsScanned;
        private long entitiesFound;
        private long entitiesRemoved;
        private long entityRemovalFailures;

        private boolean successful() {
            return successful;
        }

        private String summary() {
            return sanitize(summary);
        }

        private void fail(String summary) {
            successful = false;
            this.summary = sanitize(summary);
        }
    }
}
