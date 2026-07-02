package com.warsim.frontline.match.paper;

import com.warsim.frontline.admin.WarSimCommandExtension;
import com.warsim.frontline.admin.WarSimCommandRegistry;
import com.warsim.frontline.api.battle.*;
import com.warsim.frontline.api.classes.*;
import com.warsim.frontline.api.combat.SpawnPositionSnapshot;
import com.warsim.frontline.api.combat.SpawnProtectionService;
import com.warsim.frontline.api.combat.SpawnProtectionSnapshot;
import com.warsim.frontline.api.match.MatchParticipantState;
import com.warsim.frontline.api.match.MatchState;
import com.warsim.frontline.api.roster.TeamAssignment;
import com.warsim.frontline.api.roster.TeamSide;
import com.warsim.frontline.api.ticket.*;
import com.warsim.frontline.classes.DefaultCombatClassService;
import com.warsim.frontline.match.config.DeploymentPaperConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class PaperClassCoordinator implements Listener, BattleRuntimeListener, AutoCloseable {
    private final JavaPlugin plugin;
    private final PaperMatchCoordinator matchCoordinator;
    private final PaperBattleRuntime runtime;
    private final CombatClassConfiguration classConfiguration;
    private final DeploymentPaperConfiguration deploymentConfiguration;
    private final String classConfigurationError;
    private final String deploymentConfigurationError;
    private final java.util.Map<UUID, CombatClassId> preferences = new java.util.HashMap<>();
    private final java.util.List<AutoCloseable> commandRegistrations = new java.util.ArrayList<>();
    private AutoCloseable runtimeSubscription;
    private DefaultCombatClassService service;
    private boolean closed;

    public PaperClassCoordinator(
        JavaPlugin plugin,
        PaperMatchCoordinator matchCoordinator,
        PaperBattleRuntime runtime,
        CombatClassConfiguration classConfiguration,
        String classConfigurationError,
        DeploymentPaperConfiguration deploymentConfiguration,
        String deploymentConfigurationError
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.matchCoordinator = Objects.requireNonNull(matchCoordinator, "matchCoordinator");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.classConfiguration = Objects.requireNonNull(classConfiguration, "classConfiguration");
        this.classConfigurationError = classConfigurationError;
        this.deploymentConfiguration = Objects.requireNonNull(deploymentConfiguration, "deploymentConfiguration");
        this.deploymentConfigurationError = deploymentConfigurationError;
        createService(matchCoordinator.snapshot().matchId(), matchCoordinator.snapshot().lifecycleRevision());
    }

    public void start(WarSimCommandRegistry registry) {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        runtimeSubscription = runtime.subscribe(this);
        commandRegistrations.add(registry.register(new ClassCommand()));
        commandRegistrations.add(registry.register(new DeployCommand()));
        commandRegistrations.add(registry.register(new DeploymentCommand()));
        refreshDeploymentProviderState();
    }

    public CombatClassService service() {
        return service;
    }

    public List<String> statusLines() {
        ClassDeploymentSnapshot snapshot = service.snapshot();
        ArrayList<String> lines = new ArrayList<>();
        lines.add("§6WarSim Classes/Deployment");
        lines.add("§fClass状态：§a" + snapshot.classState());
        lines.add("§fDeployment状态：§a" + snapshot.deploymentState());
        lines.add("§fclassRevision：§a" + snapshot.classConfigurationRevision());
        lines.add("§fmatchId：§a" + snapshot.matchId());
        lines.add("§f玩家状态数：§a" + snapshot.selections().size());
        lines.add("§f部署指标 initial/respawn/rollback：§a"
            + snapshot.metrics().initialDeployments() + "/"
            + snapshot.metrics().respawnDeployments() + "/"
            + snapshot.metrics().deploymentRollbacks());
        snapshot.lastError().ifPresent(error -> lines.add("§f最近错误：§c" + error));
        return List.copyOf(lines);
    }

    public Optional<CombatEligibilitySnapshot> eligibility(UUID playerUuid) {
        return service.eligibility(playerUuid);
    }

    public void playerJoined(Player player) {
        service.playerJoined(
            player.getUniqueId(),
            matchCoordinator.snapshot().matchId(),
            Optional.ofNullable(preferences.get(player.getUniqueId())),
            Instant.now()
        );
    }

    @Override
    public void onEvent(BattleRuntimeEvent event) {
        if (closed) return;
        if (event instanceof BattleTickEvent tick) {
            tickDeployments(tick.monotonicNanos());
        } else if (event instanceof BattleMatchChangedEvent changed) {
            UUID currentMatch = changed.current().matchId();
            if (currentMatch != null
                && !Objects.equals(changed.previous().matchId(), currentMatch)) {
                createService(currentMatch, changed.current().lifecycleRevision());
                for (Player player : Bukkit.getOnlinePlayers()) {
                    playerJoined(player);
                }
            } else if (currentMatch != null) {
                service.updateLifecycle(currentMatch, changed.current().lifecycleRevision());
            }
            if (changed.current().matchState() == MatchState.RESETTING
                || changed.current().matchState() == MatchState.ENDING
                || changed.current().matchState() == MatchState.FAILED
                || changed.current().matchState() == MatchState.STOPPING
                || changed.current().matchState() == MatchState.STOPPED) {
                cancelAllDeployments("对局状态变化，部署已取消");
            }
        } else if (event instanceof BattleRuntimeClosedEvent) {
            close();
        }
    }

    @EventHandler
    public void onServiceRegister(ServiceRegisterEvent event) {
        if (event.getProvider().getService() == CombatLoadoutProvisioningService.class) {
            service.mutableMetrics().providerRegistrations.incrementAndGet();
            refreshDeploymentProviderState();
        }
    }

    @EventHandler
    public void onServiceUnregister(ServiceUnregisterEvent event) {
        if (event.getProvider().getService() == CombatLoadoutProvisioningService.class) {
            service.mutableMetrics().providerUnregistrations.incrementAndGet();
            cancelAllDeployments("装备服务已卸载，部署已取消");
            refreshDeploymentProviderState();
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        service.playerDisconnected(event.getPlayer().getUniqueId(), Instant.now());
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            service.eligibility(player.getUniqueId())
                .filter(value -> !value.eligible())
                .ifPresent(ignored -> event.setCancelled(true));
        }
    }

    public void handleCombatDeath(Player player, long expectedLifeRevision) {
        Optional<CombatEligibilitySnapshot> eligibility = service.eligibility(player.getUniqueId());
        if (eligibility.isEmpty()
            || eligibility.get().combatState() != PlayerCombatState.ALIVE
            || eligibility.get().lifeRevision() != expectedLifeRevision) {
            return;
        }
        UUID matchId = eligibility.get().matchId();
        long lifeRevision = eligibility.get().lifeRevision();
        service.markDead(player.getUniqueId(), matchId, lifeRevision, Instant.now());
        resetLifeState(player.getUniqueId(), matchId,
            service.selection(player.getUniqueId()).map(PlayerClassSelection::deploymentRevision).orElse(0L),
            lifeRevision, "death");
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        service.selection(player.getUniqueId()).ifPresent(selection -> {
            if (selection.combatState() == PlayerCombatState.DEAD
                || selection.combatState() == PlayerCombatState.NOT_DEPLOYED) {
                Location waiting = resolveWaitingSpawn().orElse(null);
                if (waiting == null) {
                    service.mutableMetrics().waitingSpawnFallbacks.incrementAndGet();
                    service.setDeploymentState(
                        DeploymentSubsystemState.SPAWN_INVALID,
                        "waiting-spawn无效，玩家保留Paper默认重生点"
                    );
                    player.sendMessage("§c等待部署点无效，已使用服务器默认重生点。请联系管理员。");
                } else {
                    event.setRespawnLocation(waiting);
                }
                if (deploymentConfiguration.enabled()) {
                    player.setGameMode(deploymentConfiguration.waitingGameMode());
                }
            }
        });
    }

    private void tickDeployments(long nowNanos) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            service.activeDeployment(player.getUniqueId())
                .filter(context -> nowNanos >= context.completesAtMonotonic())
                .ifPresent(context -> completeDeployment(player, context, nowNanos));
        }
    }

    private void completeDeployment(Player player, DeploymentContext context, long nowNanos) {
        DeploymentContext staged = context.stage(DeploymentTransactionStage.VALIDATED);
        try {
            if (!revalidate(player, staged)) return;
            CombatLoadoutProvisioningService provider = provider();
            if (provider == null || !provider.isAvailable()) {
                service.mutableMetrics().providerUnavailableRejections.incrementAndGet();
                failContext(player, staged, DeploymentFailureReason.PROVIDER_UNAVAILABLE,
                    "装备服务不可用，无法部署");
                return;
            }
            var definition = classConfiguration.byId().get(staged.requestedClass());
            if (definition == null) {
                failContext(player, staged, DeploymentFailureReason.NO_CLASS_SELECTED,
                    "兵种定义不存在");
                return;
            }
            LoadoutValidationResult validation = provider.validateLoadout(definition.equipment());
            if (!validation.valid()) {
                service.mutableMetrics().loadoutPreparationFailures.incrementAndGet();
                failContext(player, staged, DeploymentFailureReason.LOADOUT_INVALID,
                    validation.message());
                return;
            }
            staged = staged.stage(DeploymentTransactionStage.LOADOUT_PREPARED);
            service.mutableMetrics().loadoutPreparations.incrementAndGet();
            LoadoutProvisionResult prepared = provider.prepareLoadout(new LoadoutPreparationRequest(
                player.getUniqueId(), staged.matchId(), staged.lifecycleRevision(),
                staged.deploymentRevision(), staged.currentLifeRevision(), staged.proposedLifeRevision(),
                staged.requestedClass(), staged.classConfigurationRevision(), definition.equipment(),
                nowNanos, nowNanos + 30_000_000_000L
            ));
            if (!prepared.successful() || prepared.token() == null) {
                service.mutableMetrics().loadoutPreparationFailures.incrementAndGet();
                failContext(player, staged, DeploymentFailureReason.LOADOUT_INVALID,
                    prepared.message());
                return;
            }
            Location spawn = resolveSpawn(staged.teamSide()).orElse(null);
            if (spawn == null) {
                service.mutableMetrics().unsafeSpawnRejections.incrementAndGet();
                failContext(player, staged, DeploymentFailureReason.SPAWN_UNAVAILABLE,
                    "没有可用安全出生点");
                return;
            }
            TicketOperationResult charge = chargeTickets(staged);
            if (!charge.successful()) {
                failContext(player, staged, DeploymentFailureReason.TICKETS_DEPLETED, charge.message());
                return;
            }
            staged = staged.stage(DeploymentTransactionStage.TICKET_CHARGED);
            boolean committed = false;
            try {
                resetLifeState(player.getUniqueId(), staged.matchId(), staged.deploymentRevision(),
                    staged.proposedLifeRevision(), "before-grant");
                player.teleport(spawn);
                player.setGameMode(deploymentConfiguration.combatGameMode());
                staged = staged.stage(DeploymentTransactionStage.TELEPORTED);
                LoadoutProvisionResult granted = provider.grantPreparedLoadout(prepared.token());
                if (!granted.successful()) {
                    throw new IllegalStateException(granted.message());
                }
                staged = staged.stage(DeploymentTransactionStage.LOADOUT_GRANTED);
                double maximumHealth = player.getAttribute(Attribute.MAX_HEALTH) == null
                    ? 20.0 : player.getAttribute(Attribute.MAX_HEALTH).getValue();
                player.setHealth(Math.min(maximumHealth, 20.0));
                player.setFireTicks(0);
                staged = staged.stage(DeploymentTransactionStage.HEALTH_RESTORED);
                DeploymentResult alive = service.markAlive(staged, Instant.now());
                if (!alive.successful()) {
                    throw new IllegalStateException(alive.message());
                }
                committed = true;
                createSpawnProtection(player, staged, spawn, nowNanos);
                player.sendMessage("§a已部署为 " + staged.requestedClass().value());
            } finally {
                if (!committed && charge.change() != null) {
                    rollbackAfterCharge(player, staged, charge.change().operationId(), provider);
                }
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE, "[warsim-classes] 部署事务失败", exception);
            rollbackAfterCharge(player, staged, chargeOperationId(staged), provider());
            failContext(player, staged, DeploymentFailureReason.INTERNAL_ERROR,
                "部署失败，已回到等待部署状态");
        }
    }

    private void createSpawnProtection(
        Player player,
        DeploymentContext context,
        Location spawn,
        long nowNanos
    ) {
        var registration = plugin.getServer().getServicesManager()
            .getRegistration(SpawnProtectionService.class);
        if (registration == null || spawn.getWorld() == null) return;
        registration.getProvider().create(new SpawnProtectionSnapshot(
            player.getUniqueId(),
            context.matchId(),
            context.lifecycleRevision(),
            context.proposedLifeRevision(),
            context.deploymentRevision(),
            context.spawnId(),
            nowNanos,
            nowNanos + 5_000_000_000L,
            new SpawnPositionSnapshot(
                spawn.getWorld().getName(),
                spawn.getX(),
                spawn.getY(),
                spawn.getZ()
            )
        ));
    }

    private boolean revalidate(Player player, DeploymentContext context) {
        var match = matchCoordinator.snapshot();
        if (!match.matchId().equals(context.matchId())
            || match.lifecycleRevision() != context.lifecycleRevision()
            || match.state() != MatchState.PLAYING) {
            service.mutableMetrics().staleDeploymentsRejected.incrementAndGet();
            failContext(player, context, DeploymentFailureReason.STALE_CONTEXT,
                "部署已过期，请重新部署");
            return false;
        }
        var participant = matchCoordinator.participant(player.getUniqueId());
        if (participant.isEmpty()
            || participant.get().state() != MatchParticipantState.ACTIVE
            || !participant.get().matchId().equals(context.matchId())) {
            failContext(player, context, DeploymentFailureReason.NO_ACTIVE_PARTICIPANT,
                "你尚未成为有效战斗参与者");
            return false;
        }
        var assignment = matchCoordinator.assignment(player.getUniqueId());
        if (assignment.isEmpty() || !assignment.get().connected()
            || assignment.get().teamSide() != context.teamSide()) {
            failContext(player, context, DeploymentFailureReason.NO_ROSTER_ASSIGNMENT,
                "没有有效阵营分配");
            return false;
        }
        return true;
    }

    private TicketOperationResult chargeTickets(DeploymentContext context) {
        int cost = deploymentConfiguration.ticketCosts().cost(context.reason(), context.teamSide());
        if (cost == 0) {
            return new TicketOperationResult(true, false, "无需扣除票数",
                matchCoordinator.ticketService() == null ? null : matchCoordinator.ticketService().snapshot(),
                null);
        }
        TicketService tickets = matchCoordinator.ticketService();
        if (tickets == null) {
            return TicketOperationResult.rejected("票数系统不可用，无法扣除重生票",
                null);
        }
        return tickets.tryConsume(new TicketOperation(
            chargeOperationId(context), context.teamSide(), TicketOperationType.TAKE,
            cost, TicketChangeReason.RESPAWN_COST, Instant.now()
        ));
    }

    private void rollbackAfterCharge(
        Player player, DeploymentContext context, UUID chargeId,
        CombatLoadoutProvisioningService provider
    ) {
        service.mutableMetrics().deploymentRollbacks.incrementAndGet();
        int cost = deploymentConfiguration.ticketCosts().cost(context.reason(), context.teamSide());
        if (cost > 0 && matchCoordinator.ticketService() != null) {
            TicketOperationResult refund = matchCoordinator.ticketService().refund(new TicketOperation(
                refundOperationId(context), context.teamSide(), TicketOperationType.ADD,
                cost, TicketChangeReason.RESPAWN_REFUND, Instant.now()
            ), chargeId);
            if (refund.successful()) service.mutableMetrics().ticketRefunds.incrementAndGet();
            else service.mutableMetrics().ticketRefundFailures.incrementAndGet();
        }
        if (provider != null) {
            provider.clearManagedLoadout(new ManagedLoadoutClearRequest(
                player.getUniqueId(), context.matchId(), context.lifecycleRevision(),
                context.deploymentRevision(), context.proposedLifeRevision(),
                provider.providerInstanceId(), "deployment-rollback"
            ));
            provider.resetCombatLifeState(new CombatLifeResetRequest(
                player.getUniqueId(), context.matchId(), context.lifecycleRevision(),
                context.deploymentRevision(), context.proposedLifeRevision(),
                provider.providerInstanceId(), "deployment-rollback"
            ));
        }
        player.setGameMode(deploymentConfiguration.waitingGameMode());
        resolveWaitingSpawn().ifPresent(player::teleport);
        service.cancelDeployment(player.getUniqueId(), "部署失败，已回滚", Instant.now());
    }

    private void failContext(
        Player player, DeploymentContext context, DeploymentFailureReason reason, String message
    ) {
        service.cancelDeployment(player.getUniqueId(), message, Instant.now());
        player.sendMessage("§c" + message);
        plugin.getLogger().warning("[warsim-classes] deploymentRejected playerUuid="
            + player.getUniqueId() + " reason=" + reason + " matchId=" + context.matchId());
    }

    private void resetLifeState(UUID playerUuid, UUID matchId, long deploymentRevision,
                                long lifeRevision, String reason) {
        CombatLoadoutProvisioningService provider = provider();
        if (provider == null) return;
        provider.resetCombatLifeState(new CombatLifeResetRequest(
            playerUuid, matchId, matchCoordinator.snapshot().lifecycleRevision(),
            deploymentRevision, lifeRevision, provider.providerInstanceId(), reason
        ));
        provider.clearManagedLoadout(new ManagedLoadoutClearRequest(
            playerUuid, matchId, matchCoordinator.snapshot().lifecycleRevision(),
            deploymentRevision, lifeRevision, provider.providerInstanceId(), reason
        ));
        service.mutableMetrics().weaponStatesReset.incrementAndGet();
    }

    private Optional<Location> resolveWaitingSpawn() {
        return safeLocation(deploymentConfiguration.waitingSpawn());
    }

    private Optional<Location> resolveSpawn(TeamSide side) {
        DeploymentPaperConfiguration.SpawnPoint configured =
            deploymentConfiguration.teamSpawns().get(side);
        if (configured == null) return Optional.empty();
        return safeLocation(configured);
    }

    private Optional<Location> safeLocation(DeploymentPaperConfiguration.SpawnPoint spawn) {
        if (spawn == null) return Optional.empty();
        World world = Bukkit.getWorld(spawn.world());
        if (world == null) return Optional.empty();
        Location center = new Location(world, spawn.x(), spawn.y(), spawn.z(), spawn.yaw(), spawn.pitch());
        if (safe(center)) return Optional.of(center);
        int checked = 0;
        int radius = deploymentConfiguration.safeRadius();
        for (int dy = 0; dy <= 2; dy++) {
            for (int r = 1; r <= radius; r++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                        if (++checked > deploymentConfiguration.maximumSpawnCandidates()) {
                            return Optional.empty();
                        }
                        Location candidate = center.clone().add(dx, dy, dz);
                        if (safe(candidate)) return Optional.of(candidate);
                    }
                }
            }
        }
        return Optional.empty();
    }

    private boolean safe(Location location) {
        World world = location.getWorld();
        if (world == null) return false;
        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;
        if (!world.isChunkLoaded(chunkX, chunkZ)) return false;
        Block feet = world.getBlockAt(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        Block head = world.getBlockAt(location.getBlockX(), location.getBlockY() + 1, location.getBlockZ());
        Block ground = world.getBlockAt(location.getBlockX(), location.getBlockY() - 1, location.getBlockZ());
        return passable(feet) && passable(head) && ground.getType().isSolid()
            && !dangerous(feet.getType()) && !dangerous(head.getType()) && !dangerous(ground.getType());
    }

    private static boolean passable(Block block) {
        return block.isPassable() && block.getType() != Material.POWDER_SNOW;
    }

    private static boolean dangerous(Material material) {
        return material == Material.LAVA
            || material == Material.FIRE
            || material == Material.SOUL_FIRE
            || material == Material.CACTUS
            || material == Material.SWEET_BERRY_BUSH
            || material == Material.POWDER_SNOW
            || material.name().contains("MAGMA");
    }

    private void cancelAllDeployments(String reason) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            service.cancelDeployment(player.getUniqueId(), reason, Instant.now());
        }
    }

    private void createService(UUID matchId, long revision) {
        if (service != null) service.close();
        service = new DefaultCombatClassService(matchId, revision, classConfiguration,
            exception -> plugin.getLogger().log(Level.WARNING,
                "[warsim-classes] listener failed", exception));
        if (classConfigurationError != null) {
            service.setDeploymentState(DeploymentSubsystemState.DEGRADED, classConfigurationError);
        }
        refreshDeploymentProviderState();
        matchCoordinator.setCombatEligibilityService(service);
    }

    private void refreshDeploymentProviderState() {
        if (service == null) return;
        if (!deploymentConfiguration.enabled()) {
            service.setDeploymentState(DeploymentSubsystemState.DISABLED, deploymentConfigurationError);
            return;
        }
        if (deploymentConfigurationError != null) {
            service.setDeploymentState(DeploymentSubsystemState.FAILED, deploymentConfigurationError);
            return;
        }
        if (resolveWaitingSpawn().isEmpty()) {
            service.setDeploymentState(DeploymentSubsystemState.SPAWN_INVALID, "waiting-spawn无效");
            return;
        }
        CombatLoadoutProvisioningService provider = provider();
        if (provider == null || !provider.isAvailable()) {
            service.setDeploymentState(DeploymentSubsystemState.WAITING_PROVIDER, "等待Weapons装备服务注册");
            return;
        }
        service.setDeploymentState(DeploymentSubsystemState.ACTIVE, null);
    }

    private CombatLoadoutProvisioningService provider() {
        RegisteredServiceProvider<CombatLoadoutProvisioningService> registration =
            plugin.getServer().getServicesManager().getRegistration(CombatLoadoutProvisioningService.class);
        return registration == null ? null : registration.getProvider();
    }

    private static UUID chargeOperationId(DeploymentContext context) {
        return operationId(context, "RESPAWN_CHARGE");
    }

    private static UUID refundOperationId(DeploymentContext context) {
        return operationId(context, "RESPAWN_REFUND");
    }

    private static UUID operationId(DeploymentContext context, String type) {
        String value = context.matchId() + ":" + context.playerUuid() + ":"
            + context.deploymentRevision() + ":" + type;
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private Player exactPlayer(CommandSender sender, String name) {
        List<? extends Player> matches = Bukkit.getOnlinePlayers().stream()
            .filter(player -> player.getName().equalsIgnoreCase(name))
            .toList();
        if (matches.size() != 1) {
            sender.sendMessage("§c未找到唯一匹配的在线玩家。");
            return null;
        }
        return matches.getFirst();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        HandlerList.unregisterAll(this);
        if (runtimeSubscription != null) {
            try { runtimeSubscription.close(); } catch (Exception ignored) {}
        }
        for (AutoCloseable registration : commandRegistrations) {
            try { registration.close(); } catch (Exception ignored) {}
        }
        commandRegistrations.clear();
        if (service != null) service.close();
    }

    private final class ClassCommand implements WarSimCommandExtension {
        @Override public String name() { return "class"; }

        @Override
        public boolean execute(CommandSender sender, String[] arguments) {
            if (arguments.length == 0) {
                sender.sendMessage("§e用法：/warsim class <status|list|select|set|clear>");
                return true;
            }
            String action = arguments[0].toLowerCase(java.util.Locale.ROOT);
            switch (action) {
                case "status" -> {
                    if (arguments.length == 1) {
                        if (!(sender instanceof Player player)) {
                            sender.sendMessage("§e用法：/warsim class status <玩家>");
                        } else if (permission(sender, "warsim.player.class.status")) {
                            classStatus(player, player);
                        }
                    } else if (arguments.length == 2 && permission(sender, "warsim.admin.class.status")) {
                        Player target = exactPlayer(sender, arguments[1]);
                        if (target != null) classStatus(sender, target);
                    }
                }
                case "list" -> {
                    if (permission(sender, "warsim.player.class.list")) {
                        sender.sendMessage("§6可用兵种：");
                        for (CombatClassDefinition definition : service.definitions()) {
                            sender.sendMessage("§f- §a" + definition.classId().value()
                                + " §7(" + definition.displayName() + ") limit="
                                + definition.maximumPlayers());
                        }
                    }
                }
                case "select" -> {
                    if (!(sender instanceof Player player)) {
                        sender.sendMessage("§c该命令只能由玩家执行。");
                    } else if (arguments.length != 2) {
                        sender.sendMessage("§e用法：/warsim class select <classId>");
                    } else if (permission(sender, "warsim.player.class.select")) {
                        CombatClassId id = new CombatClassId(arguments[1]);
                        preferences.put(player.getUniqueId(), id);
                        DeploymentResult result = service.selectClass(
                            player.getUniqueId(), matchCoordinator.snapshot().matchId(), id, Instant.now());
                        player.sendMessage((result.successful() ? "§a" : "§c") + result.message());
                    }
                }
                case "set" -> {
                    if (arguments.length != 3) {
                        sender.sendMessage("§e用法：/warsim class set <玩家> <classId>");
                    } else if (permission(sender, "warsim.admin.class.set")) {
                        Player target = exactPlayer(sender, arguments[1]);
                        if (target != null) {
                            DeploymentResult result = service.selectClass(target.getUniqueId(),
                                matchCoordinator.snapshot().matchId(), new CombatClassId(arguments[2]), Instant.now());
                            sender.sendMessage((result.successful() ? "§a" : "§c") + result.message());
                        }
                    }
                }
                case "clear" -> {
                    if (arguments.length != 2) {
                        sender.sendMessage("§e用法：/warsim class clear <玩家>");
                    } else if (permission(sender, "warsim.admin.class.clear")) {
                        Player target = exactPlayer(sender, arguments[1]);
                        if (target != null) clearClass(sender, target);
                    }
                }
                default -> sender.sendMessage("§e用法：/warsim class <status|list|select|set|clear>");
            }
            return true;
        }

        private void classStatus(CommandSender sender, Player target) {
            var selection = service.selection(target.getUniqueId());
            sender.sendMessage("§6WarSim Class");
            sender.sendMessage("§f玩家：§a" + target.getName());
            sender.sendMessage("§f状态：§a" + selection.map(PlayerClassSelection::combatState).orElse(PlayerCombatState.NOT_DEPLOYED));
            sender.sendMessage("§f当前兵种：§a" + selection.flatMap(PlayerClassSelection::currentClass).map(CombatClassId::value).orElse("无"));
            sender.sendMessage("§f待切换兵种：§a" + selection.flatMap(PlayerClassSelection::pendingClass).map(CombatClassId::value).orElse("无"));
            sender.sendMessage("§flifeRevision：§a" + selection.map(PlayerClassSelection::lifeRevision).orElse(0L));
        }
    }

    private final class DeployCommand implements WarSimCommandExtension {
        @Override public String name() { return "deploy"; }

        @Override
        public boolean execute(CommandSender sender, String[] arguments) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§c该命令只能由玩家执行。");
                return true;
            }
            if (arguments.length == 1 && "cancel".equalsIgnoreCase(arguments[0])) {
                if (permission(sender, "warsim.player.deploy.cancel")) {
                    DeploymentResult result = service.cancelDeployment(
                        player.getUniqueId(), "玩家取消部署", Instant.now());
                    player.sendMessage((result.successful() ? "§a" : "§c") + result.message());
                }
                return true;
            }
            if (!permission(sender, "warsim.player.deploy")) return true;
            beginDeployment(player, DeploymentTrigger.MANUAL);
            return true;
        }
    }

    private final class DeploymentCommand implements WarSimCommandExtension {
        @Override public String name() { return "deployment"; }

        @Override
        public boolean execute(CommandSender sender, String[] arguments) {
            if (arguments.length == 0) {
                sender.sendMessage("§e用法：/warsim deployment <status|spawn|force>");
                return true;
            }
            String action = arguments[0].toLowerCase(java.util.Locale.ROOT);
            switch (action) {
                case "status" -> {
                    if (permission(sender, "warsim.admin.deployment.status")) {
                        statusLines().forEach(sender::sendMessage);
                    }
                }
                case "spawn" -> {
                    if (arguments.length == 2 && "list".equalsIgnoreCase(arguments[1])
                        && permission(sender, "warsim.admin.deployment.spawn.list")) {
                        sender.sendMessage("§6部署出生点");
                        sender.sendMessage("§fwaiting-spawn：§a" + deploymentConfiguration.waitingSpawn());
                        sender.sendMessage("§fattackers：§a" + deploymentConfiguration.teamSpawns().get(TeamSide.ATTACKERS));
                        sender.sendMessage("§fdefenders：§a" + deploymentConfiguration.teamSpawns().get(TeamSide.DEFENDERS));
                    } else {
                        sender.sendMessage("§e用法：/warsim deployment spawn list");
                    }
                }
                case "force" -> {
                    if (arguments.length != 2) {
                        sender.sendMessage("§e用法：/warsim deployment force <玩家>");
                    } else if (permission(sender, "warsim.admin.deployment.force")) {
                        Player target = exactPlayer(sender, arguments[1]);
                        if (target != null) beginDeployment(target, DeploymentTrigger.ADMIN_FORCE);
                    }
                }
                default -> sender.sendMessage("§e用法：/warsim deployment <status|spawn|force>");
            }
            return true;
        }
    }

    private void beginDeployment(Player player, DeploymentTrigger trigger) {
        refreshDeploymentProviderState();
        if (service.snapshot().classState() != ClassSubsystemState.ACTIVE) {
            player.sendMessage("§c兵种系统当前不可用：" + service.snapshot().classState());
            return;
        }
        if (service.snapshot().deploymentState() != DeploymentSubsystemState.ACTIVE) {
            player.sendMessage("§c部署系统当前不可用：" + service.snapshot().deploymentState());
            return;
        }
        var match = matchCoordinator.snapshot();
        if (match.state() != MatchState.PLAYING) {
            player.sendMessage("§c当前不在PLAYING状态，不能部署。");
            return;
        }
        var assignment = matchCoordinator.assignment(player.getUniqueId());
        if (assignment.isEmpty() || !assignment.get().connected()) {
            player.sendMessage("§c没有有效阵营分配，不能部署。");
            return;
        }
        var selection = service.selection(player.getUniqueId());
        CombatClassId requested = selection.flatMap(PlayerClassSelection::pendingClass)
            .or(() -> selection.flatMap(PlayerClassSelection::currentClass))
            .orElse(null);
        if (requested == null) {
            player.sendMessage("§c请先选择兵种。");
            return;
        }
        DeploymentReason reason = selection.map(PlayerClassSelection::nextDeploymentReason)
            .orElse(DeploymentReason.INITIAL_DEPLOYMENT);
        long delay = trigger == DeploymentTrigger.ADMIN_FORCE
            ? 0L : deploymentConfiguration.countdownSeconds() * 1_000_000_000L;
        DeploymentResult result = service.startDeployment(new DeploymentRequest(
            player.getUniqueId(), match.matchId(), match.lifecycleRevision(), requested,
            assignment.get().teamSide(), reason, trigger, DeploymentSpawnType.TEAM_FIXED,
            Optional.of("team_fixed"), delay
        ), System.nanoTime(), Instant.now());
        player.sendMessage((result.successful() ? "§e" : "§c") + result.message());
    }

    private void clearClass(CommandSender sender, Player target) {
        var selection = service.selection(target.getUniqueId());
        if (selection.isPresent() && selection.get().combatState() == PlayerCombatState.ALIVE) {
            Location waiting = resolveWaitingSpawn().orElse(null);
            if (waiting == null) {
                sender.sendMessage("§cwaiting-spawn无效，无法安全清理ALIVE玩家兵种。");
                return;
            }
            resetLifeState(target.getUniqueId(), selection.get().matchId(),
                selection.get().deploymentRevision(), selection.get().lifeRevision(), "class-clear");
            target.setGameMode(deploymentConfiguration.waitingGameMode());
            target.teleport(waiting);
        }
        DeploymentResult result = service.clearClass(
            target.getUniqueId(), matchCoordinator.snapshot().matchId(), Instant.now());
        sender.sendMessage((result.successful() ? "§a" : "§c") + result.message());
    }

    private static boolean permission(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) return true;
        sender.sendMessage("§c你没有权限执行该命令。");
        return false;
    }
}
