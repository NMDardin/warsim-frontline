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
import com.warsim.frontline.api.roster.TeamSide;
import com.warsim.frontline.api.ticket.*;
import com.warsim.frontline.classes.DefaultCombatClassService;
import com.warsim.frontline.match.config.DeploymentPaperConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
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

    public PaperClassCoordinator(JavaPlugin plugin, PaperMatchCoordinator matchCoordinator, PaperBattleRuntime runtime,
                                 CombatClassConfiguration classConfiguration, String classConfigurationError,
                                 DeploymentPaperConfiguration deploymentConfiguration, String deploymentConfigurationError) {
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

    public CombatClassService service() { return service; }
    public Optional<CombatEligibilitySnapshot> eligibility(UUID playerUuid) { return service.eligibility(playerUuid); }

    public List<String> statusLines() {
        ClassDeploymentSnapshot snapshot = service.snapshot();
        ArrayList<String> lines = new ArrayList<>();
        lines.add("WarSim Classes/Deployment");
        lines.add("classState=" + snapshot.classState());
        lines.add("deploymentState=" + snapshot.deploymentState());
        lines.add("classRevision=" + snapshot.classConfigurationRevision());
        lines.add("matchId=" + snapshot.matchId());
        lines.add("selections=" + snapshot.selections().size());
        lines.add("deployments initial/respawn/rollback="
            + snapshot.metrics().initialDeployments() + "/"
            + snapshot.metrics().respawnDeployments() + "/"
            + snapshot.metrics().deploymentRollbacks());
        snapshot.lastError().ifPresent(error -> lines.add("lastError=" + error));
        return List.copyOf(lines);
    }

    public void playerJoined(Player player) {
        service.playerJoined(
            player.getUniqueId(), matchCoordinator.snapshot().matchId(),
            Optional.ofNullable(preferences.get(player.getUniqueId())), Instant.now()
        );
    }

    @Override public void onEvent(BattleRuntimeEvent event) {
        if (closed) return;
        if (event instanceof BattleTickEvent tick) {
            tickDeployments(tick.monotonicNanos());
        } else if (event instanceof BattleMatchChangedEvent changed) {
            UUID currentMatch = changed.current().matchId();
            if (currentMatch != null && !Objects.equals(changed.previous().matchId(), currentMatch)) {
                preferences.clear();
                createService(currentMatch, changed.current().lifecycleRevision());
                for (Player player : Bukkit.getOnlinePlayers()) playerJoined(player);
            } else if (currentMatch != null) {
                service.updateLifecycle(currentMatch, changed.current().lifecycleRevision());
            }
            if (changed.current().matchState() == MatchState.RESETTING
                || changed.current().matchState() == MatchState.ENDING
                || changed.current().matchState() == MatchState.FAILED
                || changed.current().matchState() == MatchState.STOPPING
                || changed.current().matchState() == MatchState.STOPPED) {
                cancelAllDeployments("Match state changed; deployment cancelled");
            }
            if (changed.current().matchState() == MatchState.PLAYING) enterWaitingDeploymentForPlayingPlayers();
        } else if (event instanceof BattleRuntimeClosedEvent) {
            close();
        }
    }

    @EventHandler public void onServiceRegister(ServiceRegisterEvent event) {
        if (event.getProvider().getService() == CombatLoadoutProvisioningService.class) {
            service.mutableMetrics().providerRegistrations.incrementAndGet();
            refreshDeploymentProviderState();
        }
    }

    @EventHandler public void onServiceUnregister(ServiceUnregisterEvent event) {
        if (event.getProvider().getService() == CombatLoadoutProvisioningService.class) {
            service.mutableMetrics().providerUnregistrations.incrementAndGet();
            cancelAllDeployments("Loadout service unloaded; deployment cancelled");
            refreshDeploymentProviderState();
        }
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        preferences.remove(event.getPlayer().getUniqueId());
        service.playerDisconnected(event.getPlayer().getUniqueId(), Instant.now());
    }

    @EventHandler public void onDamage(EntityDamageEvent event) {
        if (!(event instanceof EntityDamageByEntityEvent byEntity)
            || !(event.getEntity() instanceof Player player)
            || !(byEntity.getDamager() instanceof Player damager)) return;
        var battle = matchCoordinator.snapshot();
        if (battle.state() != MatchState.PLAYING) return;
        if (matchCoordinator.participant(damager.getUniqueId())
            .filter(value -> value.matchId().equals(battle.matchId())).isEmpty()) return;
        service.eligibility(player.getUniqueId()).filter(value -> !value.eligible())
            .ifPresent(ignored -> event.setCancelled(true));
    }

    public void handleCombatDeath(Player player, long expectedLifeRevision) {
        Optional<CombatEligibilitySnapshot> eligibility = service.eligibility(player.getUniqueId());
        if (eligibility.isEmpty()
            || eligibility.get().combatState() != PlayerCombatState.ALIVE
            || eligibility.get().lifeRevision() != expectedLifeRevision) return;
        UUID matchId = eligibility.get().matchId();
        long lifeRevision = eligibility.get().lifeRevision();
        service.markDead(player.getUniqueId(), matchId, lifeRevision, Instant.now());
        resetLifeState(player.getUniqueId(), matchId,
            service.selection(player.getUniqueId()).map(PlayerClassSelection::deploymentRevision).orElse(0L),
            lifeRevision, "death");
    }

    @EventHandler public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        service.selection(player.getUniqueId()).ifPresent(selection -> {
            if (selection.combatState() == PlayerCombatState.DEAD
                || selection.combatState() == PlayerCombatState.NOT_DEPLOYED) {
                Location waiting = resolveWaitingSpawn().orElse(null);
                if (waiting == null) {
                    service.mutableMetrics().waitingSpawnFallbacks.incrementAndGet();
                    service.setDeploymentState(DeploymentSubsystemState.SPAWN_INVALID, "waiting-spawn is invalid");
                    player.sendMessage("ERROR: waiting-spawn is invalid; using server default respawn.");
                } else {
                    event.setRespawnLocation(waiting);
                }
                if (deploymentConfiguration.enabled()) {
                    matchCoordinator.assignment(player.getUniqueId()).ifPresent(assignment ->
                        service.markWaitingDeployment(
                            player.getUniqueId(), selection.matchId(), assignment.teamSide(), Instant.now()
                        ));
                    player.setGameMode(waitingGameMode());
                }
            }
        });
    }

    private void enterWaitingDeploymentForPlayingPlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            var assignment = matchCoordinator.assignment(player.getUniqueId());
            if (assignment.isEmpty() || !assignment.get().connected()) continue;
            service.selection(player.getUniqueId())
                .filter(selection -> selection.combatState() == PlayerCombatState.NOT_DEPLOYED)
                .ifPresent(selection -> {
                    service.markWaitingDeployment(
                        player.getUniqueId(), selection.matchId(), assignment.get().teamSide(), Instant.now()
                    );
                    if (deploymentConfiguration.enabled()) {
                        player.setGameMode(waitingGameMode());
                        resolveWaitingSpawn().ifPresent(player::teleport);
                        resetLifeState(
                            player.getUniqueId(), selection.matchId(),
                            selection.deploymentRevision(), selection.lifeRevision(), "enter-playing"
                        );
                    }
                });
        }
    }

    private void tickDeployments(long nowNanos) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            service.activeDeployment(player.getUniqueId())
                .filter(context -> nowNanos >= context.completesAtMonotonic())
                .ifPresent(context -> completeDeployment(player, context, nowNanos));
        }
    }

    private void completeDeployment(Player player, DeploymentContext context, long nowNanos) {
        DeploymentTransactionState tx = new DeploymentTransactionState(context.stage(DeploymentTransactionStage.VALIDATED));
        Location spawn = null;
        try {
            if (!revalidate(player, tx.context)) return;
            tx.provider = provider();
            if (tx.provider == null || !tx.provider.isAvailable()) {
                service.mutableMetrics().providerUnavailableRejections.incrementAndGet();
                failContext(player, tx.context, DeploymentFailureReason.PROVIDER_UNAVAILABLE, "Loadout service is unavailable");
                return;
            }
            var definition = classConfiguration.byId().get(tx.context.requestedClass());
            if (definition == null) {
                failContext(player, tx.context, DeploymentFailureReason.NO_CLASS_SELECTED, "Class definition is missing");
                return;
            }
            LoadoutValidationResult validation = tx.provider.validateLoadout(definition.equipment());
            if (!validation.valid()) {
                service.mutableMetrics().loadoutPreparationFailures.incrementAndGet();
                failContext(player, tx.context, DeploymentFailureReason.LOADOUT_INVALID, validation.message());
                return;
            }
            tx.context = tx.context.stage(DeploymentTransactionStage.LOADOUT_PREPARED);
            tx.loadoutPrepared = true;
            service.mutableMetrics().loadoutPreparations.incrementAndGet();
            LoadoutProvisionResult prepared = tx.provider.prepareLoadout(new LoadoutPreparationRequest(
                player.getUniqueId(), tx.context.matchId(), tx.context.lifecycleRevision(),
                tx.context.deploymentRevision(), tx.context.currentLifeRevision(), tx.context.proposedLifeRevision(),
                tx.context.requestedClass(), tx.context.classConfigurationRevision(), definition.equipment(),
                nowNanos, nowNanos + 30_000_000_000L
            ));
            if (!prepared.successful() || prepared.token() == null) {
                service.mutableMetrics().loadoutPreparationFailures.incrementAndGet();
                failContext(player, tx.context, DeploymentFailureReason.LOADOUT_INVALID, prepared.message());
                return;
            }
            spawn = resolveSpawn(tx.context.teamSide()).orElse(null);
            if (spawn == null) {
                service.mutableMetrics().unsafeSpawnRejections.incrementAndGet();
                failContext(player, tx.context, DeploymentFailureReason.SPAWN_UNAVAILABLE, "No safe spawn is available");
                return;
            }
            DeploymentResult capacity = service.revalidateDeploymentCapacity(tx.context, Instant.now());
            if (!capacity.successful()) {
                failContext(player, tx.context, capacity.failureReason(), capacity.message());
                return;
            }
            TicketOperationResult charge = chargeTickets(tx.context);
            if (!charge.successful()) {
                failContext(player, tx.context, DeploymentFailureReason.TICKETS_DEPLETED, charge.message());
                return;
            }
            if (charge.change() != null && charge.change().appliedDelta() < 0) {
                tx.ticketCharged = true;
                tx.chargeOperationId = charge.change().operationId();
            }
            tx.context = tx.context.stage(DeploymentTransactionStage.TICKET_CHARGED);
            resetLifeState(player.getUniqueId(), tx.context.matchId(), tx.context.deploymentRevision(),
                tx.context.proposedLifeRevision(), "before-grant");
            if (!player.teleport(spawn)) throw new IllegalStateException("teleport failed");
            tx.teleported = true;
            player.setGameMode(combatGameMode());
            tx.context = tx.context.stage(DeploymentTransactionStage.TELEPORTED);
            LoadoutProvisionResult granted = tx.provider.grantPreparedLoadout(prepared.token());
            if (!granted.successful()) throw new IllegalStateException(granted.message());
            tx.loadoutGranted = true;
            tx.context = tx.context.stage(DeploymentTransactionStage.LOADOUT_GRANTED);
            double maximumHealth = player.getAttribute(Attribute.MAX_HEALTH) == null
                ? 20.0 : player.getAttribute(Attribute.MAX_HEALTH).getValue();
            player.setHealth(Math.min(maximumHealth, 20.0));
            player.setFireTicks(0);
            tx.context = tx.context.stage(DeploymentTransactionStage.HEALTH_RESTORED);
            DeploymentResult alive = service.markAlive(tx.context, Instant.now());
            if (!alive.successful()) throw new IllegalStateException(alive.message());
            tx.aliveCommitted = true;
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE, "[warsim-classes] deployment transaction failed", exception);
            compensateBeforeCommit(player, tx);
            if (!tx.aliveCommitted) player.sendMessage("ERROR: Deployment failed; returned to waiting deployment");
            return;
        }
        postCommitSpawnProtection(player, tx.context, spawn, nowNanos);
        postCommitSuccessMessage(player, tx.context);
    }

    private void postCommitSpawnProtection(Player player, DeploymentContext context, Location spawn, long nowNanos) {
        try {
            createSpawnProtection(player, context, spawn, nowNanos);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING,
                "[warsim-classes] post-commit spawn protection failed; deployment remains committed", exception);
        }
    }

    private void postCommitSuccessMessage(Player player, DeploymentContext context) {
        try {
            player.sendMessage("Deployed as " + context.requestedClass().value());
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING,
                "[warsim-classes] post-commit success message failed; deployment remains committed", exception);
        }
    }

    private void createSpawnProtection(Player player, DeploymentContext context, Location spawn, long nowNanos) {
        var registration = plugin.getServer().getServicesManager().getRegistration(SpawnProtectionService.class);
        if (registration == null || spawn == null || spawn.getWorld() == null) return;
        long duration = registration.getProvider().protectionDurationNanos();
        registration.getProvider().create(new SpawnProtectionSnapshot(
            player.getUniqueId(), context.matchId(), context.lifecycleRevision(),
            context.proposedLifeRevision(), context.deploymentRevision(), context.spawnId(),
            nowNanos, nowNanos + duration,
            new SpawnPositionSnapshot(spawn.getWorld().getName(), spawn.getX(), spawn.getY(), spawn.getZ())
        ));
    }

    private GameMode waitingGameMode() { return GameMode.valueOf(deploymentConfiguration.waitingGameModeName()); }
    private GameMode combatGameMode() { return GameMode.valueOf(deploymentConfiguration.combatGameModeName()); }

    private boolean revalidate(Player player, DeploymentContext context) {
        var match = matchCoordinator.snapshot();
        if (!match.matchId().equals(context.matchId())
            || match.lifecycleRevision() != context.lifecycleRevision()
            || match.state() != MatchState.PLAYING) {
            service.mutableMetrics().staleDeploymentsRejected.incrementAndGet();
            failContext(player, context, DeploymentFailureReason.STALE_CONTEXT, "Deployment context expired");
            return false;
        }
        var participant = matchCoordinator.participant(player.getUniqueId());
        if (participant.isEmpty()
            || participant.get().state() != MatchParticipantState.ACTIVE
            || !participant.get().matchId().equals(context.matchId())) {
            failContext(player, context, DeploymentFailureReason.NO_ACTIVE_PARTICIPANT, "No active participant");
            return false;
        }
        var assignment = matchCoordinator.assignment(player.getUniqueId());
        if (assignment.isEmpty() || !assignment.get().connected()
            || assignment.get().teamSide() != context.teamSide()) {
            failContext(player, context, DeploymentFailureReason.NO_ROSTER_ASSIGNMENT, "No valid team assignment");
            return false;
        }
        return true;
    }

    private TicketOperationResult chargeTickets(DeploymentContext context) {
        int cost = deploymentConfiguration.ticketCosts().cost(context.reason(), context.teamSide());
        if (cost == 0) {
            return new TicketOperationResult(true, false, "No ticket charge required",
                matchCoordinator.ticketService() == null ? null : matchCoordinator.ticketService().snapshot(), null);
        }
        TicketService tickets = matchCoordinator.ticketService();
        if (tickets == null) return TicketOperationResult.rejected("Ticket service is unavailable", null);
        return tickets.tryConsume(new TicketOperation(
            chargeOperationId(context), context.teamSide(), TicketOperationType.TAKE,
            cost, TicketChangeReason.RESPAWN_COST, Instant.now()
        ));
    }

    private void compensateBeforeCommit(Player player, DeploymentTransactionState tx) {
        if (tx.aliveCommitted || tx.rolledBack) return;
        tx.rolledBack = true;
        service.mutableMetrics().deploymentRollbacks.incrementAndGet();
        if (tx.ticketCharged && tx.chargeOperationId != null && matchCoordinator.ticketService() != null) {
            int cost = deploymentConfiguration.ticketCosts().cost(tx.context.reason(), tx.context.teamSide());
            TicketOperationResult refund = matchCoordinator.ticketService().refund(new TicketOperation(
                refundOperationId(tx.context), tx.context.teamSide(), TicketOperationType.ADD,
                cost, TicketChangeReason.RESPAWN_REFUND, Instant.now()
            ), tx.chargeOperationId);
            if (refund.successful()) service.mutableMetrics().ticketRefunds.incrementAndGet();
            else service.mutableMetrics().ticketRefundFailures.incrementAndGet();
        }
        if (tx.provider != null) {
            tx.provider.clearManagedLoadout(new ManagedLoadoutClearRequest(
                player.getUniqueId(), tx.context.matchId(), tx.context.lifecycleRevision(),
                tx.context.deploymentRevision(), tx.context.proposedLifeRevision(),
                tx.provider.providerInstanceId(), "deployment-rollback"
            ));
            tx.provider.resetCombatLifeState(new CombatLifeResetRequest(
                player.getUniqueId(), tx.context.matchId(), tx.context.lifecycleRevision(),
                tx.context.deploymentRevision(), tx.context.proposedLifeRevision(),
                tx.provider.providerInstanceId(), "deployment-rollback"
            ));
        }
        player.setGameMode(waitingGameMode());
        resolveWaitingSpawn().ifPresent(player::teleport);
        service.cancelDeployment(player.getUniqueId(), "Deployment failed; rolled back", Instant.now());
    }

    private void failContext(Player player, DeploymentContext context, DeploymentFailureReason reason, String message) {
        service.cancelDeployment(player.getUniqueId(), message, Instant.now());
        player.sendMessage("ERROR: " + message);
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

    private Optional<Location> resolveWaitingSpawn() { return safeLocation(deploymentConfiguration.waitingSpawn()); }
    private Optional<Location> resolveSpawn(TeamSide side) {
        DeploymentPaperConfiguration.SpawnPoint configured = deploymentConfiguration.teamSpawns().get(side);
        return configured == null ? Optional.empty() : safeLocation(configured);
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
                        if (++checked > deploymentConfiguration.maximumSpawnCandidates()) return Optional.empty();
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

    private static boolean passable(Block block) { return block.isPassable() && block.getType() != Material.POWDER_SNOW; }
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
            exception -> plugin.getLogger().log(Level.WARNING, "[warsim-classes] listener failed", exception));
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
            service.setDeploymentState(DeploymentSubsystemState.SPAWN_INVALID, "waiting-spawn is invalid");
            return;
        }
        CombatLoadoutProvisioningService provider = provider();
        if (provider == null || !provider.isAvailable()) {
            service.setDeploymentState(DeploymentSubsystemState.WAITING_PROVIDER, "Waiting for Weapons loadout service");
            return;
        }
        service.setDeploymentState(DeploymentSubsystemState.ACTIVE, null);
    }

    private CombatLoadoutProvisioningService provider() {
        RegisteredServiceProvider<CombatLoadoutProvisioningService> registration =
            plugin.getServer().getServicesManager().getRegistration(CombatLoadoutProvisioningService.class);
        return registration == null ? null : registration.getProvider();
    }

    private static UUID chargeOperationId(DeploymentContext context) { return operationId(context, "RESPAWN_CHARGE"); }
    private static UUID refundOperationId(DeploymentContext context) { return operationId(context, "RESPAWN_REFUND"); }
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
            sender.sendMessage("ERROR: Expected exactly one online player match.");
            return null;
        }
        return matches.getFirst();
    }

    @Override public void close() {
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
        preferences.clear();
        if (service != null) service.close();
    }

    private static final class DeploymentTransactionState {
        private DeploymentContext context;
        private CombatLoadoutProvisioningService provider;
        private boolean loadoutPrepared;
        private boolean ticketCharged;
        private UUID chargeOperationId;
        private boolean teleported;
        private boolean loadoutGranted;
        private boolean aliveCommitted;
        private boolean rolledBack;
        private DeploymentTransactionState(DeploymentContext context) { this.context = context; }
    }

    private final class ClassCommand implements WarSimCommandExtension {
        @Override public String name() { return "class"; }
        @Override public boolean execute(CommandSender sender, String[] arguments) {
            if (arguments.length == 0) {
                sender.sendMessage("Usage: /warsim class <status|list|select|set|clear>");
                return true;
            }
            String action = arguments[0].toLowerCase(java.util.Locale.ROOT);
            switch (action) {
                case "status" -> {
                    if (arguments.length == 1 && sender instanceof Player player
                        && permission(sender, "warsim.player.class.status")) {
                        classStatus(player, player);
                    } else if (arguments.length == 2 && permission(sender, "warsim.admin.class.status")) {
                        Player target = exactPlayer(sender, arguments[1]);
                        if (target != null) classStatus(sender, target);
                    }
                }
                case "list" -> {
                    if (permission(sender, "warsim.player.class.list")) {
                        sender.sendMessage("Available classes:");
                        for (CombatClassDefinition definition : service.definitions()) {
                            sender.sendMessage("- " + definition.classId().value()
                                + " (" + definition.displayName() + ") limit=" + definition.maximumPlayers());
                        }
                    }
                }
                case "select" -> {
                    if (!(sender instanceof Player player)) {
                        sender.sendMessage("ERROR: Player only command.");
                    } else if (arguments.length != 2) {
                        sender.sendMessage("Usage: /warsim class select <classId>");
                    } else if (permission(sender, "warsim.player.class.select")) {
                        CombatClassId id = new CombatClassId(arguments[1]);
                        preferences.put(player.getUniqueId(), id);
                        DeploymentResult result = service.selectClass(
                            player.getUniqueId(), matchCoordinator.snapshot().matchId(), id, Instant.now());
                        player.sendMessage((result.successful() ? "OK: " : "ERROR: ") + result.message());
                    }
                }
                case "set" -> {
                    if (arguments.length != 3) {
                        sender.sendMessage("Usage: /warsim class set <player> <classId>");
                    } else if (permission(sender, "warsim.admin.class.set")) {
                        Player target = exactPlayer(sender, arguments[1]);
                        if (target != null) {
                            DeploymentResult result = service.selectClass(target.getUniqueId(),
                                matchCoordinator.snapshot().matchId(), new CombatClassId(arguments[2]), Instant.now());
                            sender.sendMessage((result.successful() ? "OK: " : "ERROR: ") + result.message());
                        }
                    }
                }
                case "clear" -> {
                    if (arguments.length != 2) {
                        sender.sendMessage("Usage: /warsim class clear <player>");
                    } else if (permission(sender, "warsim.admin.class.clear")) {
                        Player target = exactPlayer(sender, arguments[1]);
                        if (target != null) clearClass(sender, target);
                    }
                }
                default -> sender.sendMessage("Usage: /warsim class <status|list|select|set|clear>");
            }
            return true;
        }
        private void classStatus(CommandSender sender, Player target) {
            var selection = service.selection(target.getUniqueId());
            sender.sendMessage("WarSim Class");
            sender.sendMessage("player=" + target.getName());
            sender.sendMessage("state=" + selection.map(PlayerClassSelection::combatState).orElse(PlayerCombatState.NOT_DEPLOYED));
            sender.sendMessage("currentClass=" + selection.flatMap(PlayerClassSelection::currentClass).map(CombatClassId::value).orElse("none"));
            sender.sendMessage("pendingClass=" + selection.flatMap(PlayerClassSelection::pendingClass).map(CombatClassId::value).orElse("none"));
            sender.sendMessage("lifeRevision=" + selection.map(PlayerClassSelection::lifeRevision).orElse(0L));
        }
    }

    private final class DeployCommand implements WarSimCommandExtension {
        @Override public String name() { return "deploy"; }
        @Override public boolean execute(CommandSender sender, String[] arguments) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("ERROR: Player only command.");
                return true;
            }
            if (arguments.length == 1 && "cancel".equalsIgnoreCase(arguments[0])) {
                if (permission(sender, "warsim.player.deploy.cancel")) {
                    DeploymentResult result = service.cancelDeployment(
                        player.getUniqueId(), "Player cancelled deployment", Instant.now());
                    player.sendMessage((result.successful() ? "OK: " : "ERROR: ") + result.message());
                }
                return true;
            }
            if (permission(sender, "warsim.player.deploy")) beginDeployment(player, DeploymentTrigger.MANUAL);
            return true;
        }
    }

    private final class DeploymentCommand implements WarSimCommandExtension {
        @Override public String name() { return "deployment"; }
        @Override public boolean execute(CommandSender sender, String[] arguments) {
            if (arguments.length == 0) {
                sender.sendMessage("Usage: /warsim deployment <status|spawn|force>");
                return true;
            }
            String action = arguments[0].toLowerCase(java.util.Locale.ROOT);
            switch (action) {
                case "status" -> {
                    if (permission(sender, "warsim.admin.deployment.status")) statusLines().forEach(sender::sendMessage);
                }
                case "spawn" -> {
                    if (arguments.length == 2 && "list".equalsIgnoreCase(arguments[1])
                        && permission(sender, "warsim.admin.deployment.spawn.list")) {
                        sender.sendMessage("Deployment spawns:");
                        sender.sendMessage("waiting-spawn=" + deploymentConfiguration.waitingSpawn());
                        sender.sendMessage("attackers=" + deploymentConfiguration.teamSpawns().get(TeamSide.ATTACKERS));
                        sender.sendMessage("defenders=" + deploymentConfiguration.teamSpawns().get(TeamSide.DEFENDERS));
                    } else {
                        sender.sendMessage("Usage: /warsim deployment spawn list");
                    }
                }
                case "force" -> {
                    if (arguments.length != 2) {
                        sender.sendMessage("Usage: /warsim deployment force <player>");
                    } else if (permission(sender, "warsim.admin.deployment.force")) {
                        Player target = exactPlayer(sender, arguments[1]);
                        if (target != null) beginDeployment(target, DeploymentTrigger.ADMIN_FORCE);
                    }
                }
                default -> sender.sendMessage("Usage: /warsim deployment <status|spawn|force>");
            }
            return true;
        }
    }

    private void beginDeployment(Player player, DeploymentTrigger trigger) {
        refreshDeploymentProviderState();
        if (service.snapshot().classState() != ClassSubsystemState.ACTIVE) {
            player.sendMessage("ERROR: Class system unavailable: " + service.snapshot().classState());
            return;
        }
        if (service.snapshot().deploymentState() != DeploymentSubsystemState.ACTIVE) {
            player.sendMessage("ERROR: Deployment system unavailable: " + service.snapshot().deploymentState());
            return;
        }
        var match = matchCoordinator.snapshot();
        if (match.state() != MatchState.PLAYING) {
            player.sendMessage("ERROR: Match is not PLAYING; cannot deploy.");
            return;
        }
        var assignment = matchCoordinator.assignment(player.getUniqueId());
        if (assignment.isEmpty() || !assignment.get().connected()) {
            player.sendMessage("ERROR: No valid team assignment; cannot deploy.");
            return;
        }
        var selection = service.selection(player.getUniqueId());
        if (selection.isPresent()
            && (selection.get().combatState() == PlayerCombatState.ALIVE
                || selection.get().combatState() == PlayerCombatState.DEPLOYING
                || selection.get().combatState() == PlayerCombatState.CLOSED)) {
            player.sendMessage("ERROR: Current state cannot deploy.");
            return;
        }
        CombatClassId requested = selection.flatMap(PlayerClassSelection::currentClass).orElse(null);
        if (requested == null) {
            player.sendMessage("ERROR: Select a class first.");
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
        player.sendMessage((result.successful() ? "OK: " : "ERROR: ") + result.message());
    }

    private void clearClass(CommandSender sender, Player target) {
        var selection = service.selection(target.getUniqueId());
        if (selection.isPresent() && selection.get().combatState() == PlayerCombatState.ALIVE) {
            Location waiting = resolveWaitingSpawn().orElse(null);
            if (waiting == null) {
                sender.sendMessage("ERROR: waiting-spawn is invalid; cannot safely clear an ALIVE player class.");
                return;
            }
            resetLifeState(target.getUniqueId(), selection.get().matchId(),
                selection.get().deploymentRevision(), selection.get().lifeRevision(), "class-clear");
            target.setGameMode(waitingGameMode());
            target.teleport(waiting);
        }
        DeploymentResult result = service.clearClass(
            target.getUniqueId(), matchCoordinator.snapshot().matchId(), Instant.now());
        if (result.successful()) preferences.remove(target.getUniqueId());
        sender.sendMessage((result.successful() ? "OK: " : "ERROR: ") + result.message());
    }

    private static boolean permission(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) return true;
        sender.sendMessage("ERROR: You do not have permission for this command.");
        return false;
    }
}
