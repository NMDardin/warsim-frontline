package com.warsim.frontline.destruction;

import com.warsim.frontline.api.battle.BattleMatchChangedEvent;
import com.warsim.frontline.api.battle.BattleRuntimeEvent;
import com.warsim.frontline.api.battle.BattleRuntimeListener;
import com.warsim.frontline.api.battle.BattleRuntimeSnapshot;
import com.warsim.frontline.api.battle.WarSimBattleRuntime;
import com.warsim.frontline.api.match.MatchResetContext;
import com.warsim.frontline.api.match.MatchResetResult;
import com.warsim.frontline.api.match.MatchState;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class PaperDestructionCoordinator implements Listener, BattleRuntimeListener, AutoCloseable {
    private final JavaPlugin plugin;
    private final WarSimBattleRuntime runtime;
    private final DestructionPaperConfiguration configuration;
    private final String configurationError;
    private final Map<UUID, MatchDestructionLedger> ledgers = new LinkedHashMap<>();
    private AutoCloseable runtimeSubscription;
    private boolean closed;
    private long totalExplosionEventsObserved;
    private long totalExplosionBlocksAllowed;
    private long totalExplosionBlocksRejected;
    private long totalPlayerBreaksAllowed;
    private long totalPlayerBreaksRejected;
    private long totalProtectedRejections;
    private long totalMaterialRejections;
    private long totalTileStateRejections;
    private long totalLimitRejections;
    private long restoreAttempts;
    private long restoreSuccesses;
    private long restoreFailures;
    private UUID lastRestoreMatchId;
    private long lastRestoreRevision;
    private long lastRestoreBlocks;
    private long lastRestoreDurationMillis;
    private String lastErrorSummary = "none";

    public PaperDestructionCoordinator(
        JavaPlugin plugin,
        WarSimBattleRuntime runtime,
        DestructionPaperConfiguration configuration,
        String configurationError
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.configurationError = configurationError;
    }

    public void start() {
        if (closed || !configuration.enabled() || configurationError != null) {
            return;
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        runtimeSubscription = runtime.subscribe(this);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!configuration.enabled() || !configuration.recordEntityExplosions()) return;
        totalExplosionEventsObserved++;
        filterExplosionBlocks(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (!configuration.enabled() || !configuration.recordBlockExplosions()) return;
        totalExplosionEventsObserved++;
        filterExplosionBlocks(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!configuration.enabled() || !configuration.recordPlayerBlockBreaks()) return;
        BattleRuntimeSnapshot battle = runtime.snapshot();
        CaptureDecision decision = captureOriginal(event.getBlock(), battle, CaptureMode.BREAK);
        if (decision.allowed()) {
            totalPlayerBreaksAllowed++;
        } else {
            totalPlayerBreaksRejected++;
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!configuration.enabled() || !configuration.recordPlayerBlockPlaces()) return;
        BattleRuntimeSnapshot battle = runtime.snapshot();
        CaptureDecision decision = captureOriginal(
            event.getBlockPlaced(),
            event.getBlockReplacedState(),
            battle,
            CaptureMode.PLACE
        );
        if (!decision.allowed()) {
            event.setCancelled(true);
        }
    }

    private void filterExplosionBlocks(List<Block> blocks) {
        BattleRuntimeSnapshot battle = runtime.snapshot();
        Iterator<Block> iterator = blocks.iterator();
        while (iterator.hasNext()) {
            Block block = iterator.next();
            if (!recordableBattle(battle)) {
                if (configuration.worlds().contains(block.getWorld().getName())) {
                    iterator.remove();
                    totalExplosionBlocksRejected++;
                }
                continue;
            }
            CaptureDecision decision = captureOriginal(block, battle, CaptureMode.EXPLOSION);
            if (decision.allowed()) {
                totalExplosionBlocksAllowed++;
            } else {
                totalExplosionBlocksRejected++;
                iterator.remove();
            }
        }
    }

    private CaptureDecision captureOriginal(
        Block block,
        BattleRuntimeSnapshot battle,
        CaptureMode mode
    ) {
        return captureOriginal(block, block.getState(), battle, mode);
    }

    private CaptureDecision captureOriginal(
        Block block,
        BlockState originalState,
        BattleRuntimeSnapshot battle,
        CaptureMode mode
    ) {
        if (!recordableBattle(battle)) return CaptureDecision.reject(RejectReason.STATE);
        if (!configuration.worlds().contains(block.getWorld().getName())) {
            return CaptureDecision.reject(RejectReason.WORLD);
        }
        if (protectedRegion(block)) {
            totalProtectedRejections++;
            return CaptureDecision.reject(RejectReason.PROTECTED);
        }
        if (originalState instanceof TileState) {
            totalTileStateRejections++;
            return CaptureDecision.reject(RejectReason.TILE_STATE);
        }
        if (mode != CaptureMode.PLACE && originalState.getType().isAir()) {
            return CaptureDecision.reject(RejectReason.MATERIAL);
        }
        if (!allowedMaterial(block.getType())) {
            totalMaterialRejections++;
            return CaptureDecision.reject(RejectReason.MATERIAL);
        }
        try {
            boolean captured = captureSnapshot(block, originalState, battle);
            return captured ? CaptureDecision.allow() : CaptureDecision.reject(RejectReason.LIMIT);
        } catch (RuntimeException exception) {
            lastErrorSummary = sanitize("capture failed");
            plugin.getLogger().log(Level.WARNING, "[warsim-destruction] Block capture failed", exception);
            return CaptureDecision.reject(RejectReason.CAPTURE);
        }
    }

    private synchronized boolean captureSnapshot(
        Block block,
        BlockState originalState,
        BattleRuntimeSnapshot battle
    ) {
        MatchDestructionLedger ledger = ledgers.computeIfAbsent(
            battle.matchId(),
            ignored -> new MatchDestructionLedger(battle.matchId(), battle.lifecycleRevision())
        );
        if (ledger.lifecycleRevision() != battle.lifecycleRevision()) {
            return false;
        }
        DestructionBlockKey key = new DestructionBlockKey(
            block.getWorld().getName(), block.getX(), block.getY(), block.getZ()
        );
        if (ledger.contains(key)) {
            return true;
        }
        if (ledger.size() >= configuration.maximumBlocksPerMatch()) {
            totalLimitRejections++;
            lastErrorSummary = "maximum-blocks-per-match reached";
            plugin.getLogger().warning("[warsim-destruction] maximum-blocks-per-match reached matchId="
                + battle.matchId() + " limit=" + configuration.maximumBlocksPerMatch());
            return false;
        }
        ledger.putIfAbsent(new DestructionBlockSnapshot(
            key,
            originalState.getBlockData().getAsString(),
            originalState.getType().name(),
            battle.matchId(),
            battle.lifecycleRevision(),
            ledger.nextOrder(),
            Instant.now()
        ));
        return true;
    }

    public MatchResetResult restoreForReset(MatchResetContext context) {
        Objects.requireNonNull(context, "context");
        restoreAttempts++;
        lastRestoreMatchId = context.matchId();
        lastRestoreRevision = context.lifecycleRevision();
        lastRestoreBlocks = 0;
        Instant started = Instant.now();
        MatchDestructionLedger ledger;
        synchronized (this) {
            ledger = ledgers.get(context.matchId());
        }
        if (ledger == null || ledger.size() == 0) {
            restoreSuccesses++;
            lastRestoreDurationMillis = Duration.between(started, Instant.now()).toMillis();
            lastErrorSummary = "none";
            return new MatchResetResult(true, "Destruction restore complete blocks=0");
        }
        if (ledger.lifecycleRevision() != context.lifecycleRevision()) {
            restoreFailures++;
            lastErrorSummary = "Destruction ledger revision mismatch";
            return MatchResetResult.failure(lastErrorSummary);
        }
        if (ledger.size() > configuration.maximumBlocksPerReset()) {
            restoreFailures++;
            lastErrorSummary = "maximum-blocks-per-reset exceeded";
            return MatchResetResult.failure(lastErrorSummary);
        }

        long restored = 0;
        long failed = 0;
        for (DestructionBlockSnapshot snapshot : ledger.snapshotsReverseOrder()) {
            try {
                if (restoreSnapshot(snapshot)) {
                    restored++;
                } else {
                    failed++;
                }
            } catch (RuntimeException exception) {
                failed++;
                plugin.getLogger().log(
                    Level.WARNING,
                    "[warsim-destruction] Block restore threw matchId=" + context.matchId()
                        + " world=" + snapshot.key().worldName(),
                    exception
                );
            }
        }
        lastRestoreBlocks = restored;
        lastRestoreDurationMillis = Duration.between(started, Instant.now()).toMillis();
        if (failed > 0) {
            restoreFailures++;
            lastErrorSummary = "Destruction restore failed blocks=" + failed;
            return MatchResetResult.failure(lastErrorSummary);
        }
        synchronized (this) {
            ledgers.remove(context.matchId());
        }
        restoreSuccesses++;
        lastErrorSummary = "none";
        return new MatchResetResult(true, "Destruction restore complete blocks=" + restored);
    }

    private boolean restoreSnapshot(DestructionBlockSnapshot snapshot) {
        World world = Bukkit.getWorld(snapshot.key().worldName());
        if (world == null) {
            lastErrorSummary = "restore world is not loaded";
            return false;
        }
        int chunkX = snapshot.key().x() >> 4;
        int chunkZ = snapshot.key().z() >> 4;
        if (!world.isChunkLoaded(chunkX, chunkZ) && !world.loadChunk(chunkX, chunkZ, false)) {
            lastErrorSummary = "restore chunk is not loaded";
            return false;
        }
        BlockData blockData = Bukkit.createBlockData(snapshot.blockDataString());
        world.getBlockAt(snapshot.key().x(), snapshot.key().y(), snapshot.key().z())
            .setBlockData(blockData, false);
        return true;
    }

    private boolean recordableBattle(BattleRuntimeSnapshot battle) {
        return battle.available()
            && battle.matchId() != null
            && battle.matchState() == MatchState.PLAYING;
    }

    private boolean protectedRegion(Block block) {
        String world = block.getWorld().getName();
        double x = block.getX();
        double y = block.getY();
        double z = block.getZ();
        return configuration.protectedRegions().stream()
            .anyMatch(region -> region.contains(world, x, y, z));
    }

    private boolean allowedMaterial(org.bukkit.Material material) {
        return configuration.allowList().contains(material)
            && !configuration.denyList().contains(material);
    }

    @Override
    public synchronized void onEvent(BattleRuntimeEvent event) {
        if (event instanceof BattleMatchChangedEvent changed) {
            UUID current = changed.current().matchId();
            if (current != null && changed.previous().matchId() != null
                && !current.equals(changed.previous().matchId())) {
                ledgers.keySet().removeIf(matchId -> !matchId.equals(current));
            }
        }
    }

    public synchronized List<String> statusLines() {
        UUID currentMatch = runtime.snapshot().matchId();
        int currentBlocks = currentMatch == null || !ledgers.containsKey(currentMatch)
            ? 0 : ledgers.get(currentMatch).size();
        return List.of(
            "§6WarSim Destruction",
            "§fEnabled: §a" + configuration.enabled(),
            "§fConfig error: §a" + (configurationError == null ? "none" : configurationError),
            "§fCurrent Match blocks: §a" + currentBlocks + "/" + configuration.maximumBlocksPerMatch(),
            "§fLedgers: §a" + ledgers.size(),
            "§fExplosion allowed/rejected: §a" + totalExplosionBlocksAllowed + "/" + totalExplosionBlocksRejected,
            "§fProtected/material/Tile/limit rejected: §a"
                + totalProtectedRejections + "/" + totalMaterialRejections + "/"
                + totalTileStateRejections + "/" + totalLimitRejections,
            "§fRestore attempts/success/failure: §a"
                + restoreAttempts + "/" + restoreSuccesses + "/" + restoreFailures,
            "§fLast restore blocks: §a" + lastRestoreBlocks,
            "§fLast error: §a" + lastErrorSummary
        );
    }

    @Override
    public synchronized void close() {
        closed = true;
        HandlerList.unregisterAll(this);
        if (runtimeSubscription != null) {
            try {
                runtimeSubscription.close();
            } catch (Exception ignored) {
            }
            runtimeSubscription = null;
        }
        ledgers.clear();
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) return "Destruction failed";
        String cleaned = value.replaceAll("\\p{Cc}", "").trim();
        return cleaned.length() <= 128 ? cleaned : cleaned.substring(0, 128);
    }

    private enum CaptureMode {
        EXPLOSION, BREAK, PLACE
    }

    private enum RejectReason {
        STATE, WORLD, PROTECTED, TILE_STATE, MATERIAL, LIMIT, CAPTURE
    }

    private record CaptureDecision(boolean allowed, RejectReason reason) {
        private static CaptureDecision allow() {
            return new CaptureDecision(true, null);
        }

        private static CaptureDecision reject(RejectReason reason) {
            return new CaptureDecision(false, reason);
        }
    }
}
