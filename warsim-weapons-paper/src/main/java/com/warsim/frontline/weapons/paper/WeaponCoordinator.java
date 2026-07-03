package com.warsim.frontline.weapons.paper;

import com.warsim.frontline.api.battle.*;
import com.warsim.frontline.api.combat.CombatPolicyService;
import com.warsim.frontline.api.combat.FeedbackChannel;
import com.warsim.frontline.api.combat.FeedbackMessage;
import com.warsim.frontline.api.combat.FeedbackPriority;
import com.warsim.frontline.api.combat.PlayerFeedbackService;
import com.warsim.frontline.api.match.MatchState;
import com.warsim.frontline.api.weapon.*;
import com.warsim.frontline.weapons.DefaultWeaponService;
import java.time.Instant;
import java.util.*;
import java.util.logging.Level;
import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

final class WeaponCoordinator implements Listener, BattleRuntimeListener, AutoCloseable {
    private final WarSimWeaponsPlugin plugin;
    private final WarSimBattleRuntime runtime;
    private final WeaponPaperConfiguration configuration;
    private final DefaultWeaponService service;
    private final CraftEngineWeaponGateway gateway;
    private final WeaponLoadoutProvisioningService loadoutProvider;
    private final PaperTargetSampler sampler;
    private final DamageAttributionRegistry attribution = new DamageAttributionRegistry();
    private final PaperDamageAdapter damage;
    private final WeaponFeedback feedback = new WeaponFeedback();
    private final Map<UUID, ReloadKey> reloading = new HashMap<>();
    private final AutoCloseable runtimeSubscription;
    private long tick;
    private volatile boolean closed;

    WeaponCoordinator(
        WarSimWeaponsPlugin plugin, WarSimBattleRuntime runtime,
        WeaponPaperConfiguration configuration
    ) {
        this.plugin = plugin;
        this.runtime = runtime;
        this.configuration = configuration;
        this.service = new DefaultWeaponService(configuration.core());
        this.gateway = new CraftEngineWeaponGateway(service.definitions());
        this.loadoutProvider = new WeaponLoadoutProvisioningService(plugin, service, gateway);
        this.sampler = new PaperTargetSampler(runtime, configuration.core(), service);
        this.damage = new PaperDamageAdapter(plugin, runtime, attribution, service);
        this.runtimeSubscription = runtime.subscribe(this);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        for (Player player : Bukkit.getOnlinePlayers()) {
            reconcilePlayerInventory(player, "startup");
        }
    }

    DefaultWeaponService service() { return service; }
    CraftEngineWeaponGateway gateway() { return gateway; }
    WeaponLoadoutProvisioningService loadoutProvider() { return loadoutProvider; }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (closed || event.getHand() != EquipmentSlot.HAND
            || event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Optional<WeaponId> identified = gateway.identify(event.getItem());
        if (identified.isEmpty()) return;
        if (configuration.cancelVanillaInteraction()) {
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.DENY);
            event.setCancelled(true);
        }
        fire(event.getPlayer(), identified.get());
    }

    private void fire(Player shooter, WeaponId weaponId) {
        BattleRuntimeSnapshot battle = runtime.snapshot();
        if (!battle.available() || battle.matchState() != MatchState.PLAYING) {
            shotFeedback(shooter, rejected(
                shooter, weaponId, battle, ShotOutcome.REJECTED_NOT_PLAYING,
                WeaponFailureReason.NOT_PLAYING
            ));
            return;
        }
        var player = runtime.player(shooter.getUniqueId());
        if (player.isEmpty() || !player.get().activeFor(battle.matchId())) {
            ShotOutcome outcome = player.isPresent()
                && player.get().assignment().isEmpty()
                ? ShotOutcome.REJECTED_NO_ASSIGNMENT : ShotOutcome.REJECTED_NOT_ACTIVE;
            shotFeedback(shooter, rejected(
                shooter, weaponId, battle, outcome,
                outcome == ShotOutcome.REJECTED_NO_ASSIGNMENT
                    ? WeaponFailureReason.NO_ASSIGNMENT : WeaponFailureReason.NOT_ACTIVE
            ));
            return;
        }
        long now = System.nanoTime();
        WeaponOperationResult preflight = service.canFire(
            shooter.getUniqueId(), battle.matchId(), weaponId, now
        );
        if (!preflight.successful()) {
            shotFeedback(shooter, rejected(
                shooter, weaponId, battle, map(preflight.reason()), preflight.reason()
            ));
            return;
        }
        WeaponDefinition definition = service.definition(weaponId).orElseThrow();
        var eye = shooter.getEyeLocation();
        long seed = java.util.concurrent.ThreadLocalRandom.current().nextLong();
        Vector3 direction;
        try {
            direction = service.spreadDirection(
                weaponId,
                new Vector3(
                    eye.getDirection().getX(), eye.getDirection().getY(),
                    eye.getDirection().getZ()
                ),
                seed
            );
        } catch (RuntimeException exception) {
            internalFailure(shooter, exception);
            return;
        }
        OptionalDouble blockDistance;
        try {
            RayTraceResult block = shooter.getWorld().rayTraceBlocks(
                eye, new Vector(direction.x(), direction.y(), direction.z()),
                definition.maximumRange(), FluidCollisionMode.NEVER, true
            );
            blockDistance = block == null ? OptionalDouble.empty()
                : OptionalDouble.of(block.getHitPosition().distance(eye.toVector()));
        } catch (RuntimeException exception) {
            plugin.getLogger().log(
                Level.SEVERE, "[warsim-weapons] Block ray trace failed; shot was safely rejected.", exception
            );
            shotFeedback(shooter, rejected(
                shooter, weaponId, battle, ShotOutcome.REJECTED_INTERNAL_ERROR,
                WeaponFailureReason.BLOCK_TRACE_FAILED
            ));
            return;
        }
        List<HitCandidate> candidates =
            sampler.sample(shooter, battle.matchId(), definition.maximumRange());
        if ((System.nanoTime() - now) / 1_000_000L
            > configuration.core().maximumDeltaMillis()) {
            service.recordStaleShot();
            shotFeedback(shooter, rejected(
                shooter, weaponId, battle, ShotOutcome.REJECTED_INVALID_STATE,
                WeaponFailureReason.STALE_MATCH
            ));
            return;
        }
        ShotRequest request = new ShotRequest(
            ShotId.random(), battle.matchId(), battle.lifecycleRevision(),
            shooter.getUniqueId(), weaponId, shooter.getWorld().getName(),
            new Vector3(eye.getX(), eye.getY(), eye.getZ()),
            direction, now, seed
        );
        WeaponDamagePolicy damagePolicy = damagePolicy();
        ShotResult result = service.fire(
            new ShotContext(request, candidates, blockDistance),
            target -> runtime.relation(shooter.getUniqueId(), target),
            damagePolicy
        );
        DamageApplicationResult damageResult = result.requestedDamage() > 0
            ? damage.apply(result) : DamageApplicationResult.NOT_APPLICABLE;
        shotFeedback(shooter, result, damageResult);
        updateDisplay(shooter, weaponId);
        shooter.getWorld().playSound(
            shooter.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_BLAST,
            SoundCategory.PLAYERS, .35f, 1.6f
        );
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (closed || !configuration.cancelVanillaDrop()) return;
        Optional<WeaponId> weapon = gateway.identify(event.getItemDrop().getItemStack());
        if (weapon.isEmpty()) return;
        event.setCancelled(true);
        BattleRuntimeSnapshot battle = runtime.snapshot();
        if (!battle.available() || battle.matchState() != MatchState.PLAYING
            || runtime.player(event.getPlayer().getUniqueId())
                .filter(value -> value.activeFor(battle.matchId())).isEmpty()) {
            notice(event.getPlayer(), "§c当前状态不能装填");
            return;
        }
        WeaponOperationResult result = service.startReload(
            event.getPlayer().getUniqueId(), battle.matchId(), weapon.get(), System.nanoTime()
        );
        if (result.successful()) {
            reloading.put(event.getPlayer().getUniqueId(),
                new ReloadKey(battle.matchId(), weapon.get()));
            notice(event.getPlayer(), "§e开始装填");
            updateDisplay(event.getPlayer(), weapon.get());
        } else {
            notice(event.getPlayer(), "§e" + result.message());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMelee(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player shooter)
            || !(event.getEntity() instanceof Player target)
            || gateway.identify(shooter.getInventory().getItemInMainHand()).isEmpty()) return;
        if (!damage.isApplying(shooter, target)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onWeaponDamageMonitor(EntityDamageByEntityEvent event) {
        damage.handleDamageEvent(event);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        loadoutProvider.removeManagedDeathDrops(event);
        attribution.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID player = event.getPlayer().getUniqueId();
        reloading.remove(player);
        loadoutProvider.clearPlayer(event.getPlayer(), "quit");
        service.clearPlayer(player);
        clearFeedback(player);
        attribution.remove(player);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        reconcilePlayerInventory(event.getPlayer(), "join");
    }

    @EventHandler
    public void onCraftEngineReload(CraftEngineReloadEvent event) {
        List<String> unavailable = gateway.unavailableBindings();
        if (unavailable.isEmpty()) {
            plugin.getLogger().info("[warsim-weapons] CraftEngine weapon bindings verified.");
        } else {
            plugin.getLogger().warning(
                "[warsim-weapons] CraftEngine is missing test weapon items: " + unavailable
            );
        }
    }

    @Override
    public void onEvent(BattleRuntimeEvent event) {
        if (closed) return;
        if (event instanceof BattleTickEvent tickEvent) {
            tick = tickEvent.tick();
            if (tick % configuration.core().reloadCheckIntervalTicks() == 0) {
                processReloads(tickEvent.monotonicNanos());
                updateDisplays(tickEvent.snapshot());
            }
        } else if (event instanceof BattleMatchChangedEvent changed) {
            if (changed.previous().matchId() != null
                && (!Objects.equals(
                    changed.previous().matchId(), changed.current().matchId()
                ) || changed.current().matchState() == MatchState.RESETTING
                || changed.current().matchState() == MatchState.ENDING
                || changed.current().matchState() == MatchState.FAILED
                || changed.current().matchState() == MatchState.STOPPING
                || changed.current().matchState() == MatchState.STOPPED)) {
                loadoutProvider.clearMatch(changed.previous().matchId(), "runtime-event");
                service.clearMatch(changed.previous().matchId());
                reloading.clear();
                attribution.clear();
                clearAllFeedback();
            }
        } else if (event instanceof BattleParticipantEvent participant && !participant.joined()) {
            Player player = Bukkit.getPlayer(participant.playerUuid());
            if (player != null) {
                loadoutProvider.clearPlayer(player, "participant-left");
            } else {
                loadoutProvider.clearPlayer(participant.playerUuid(), "participant-left");
            }
            service.clearPlayer(participant.playerUuid());
            clearFeedback(participant.playerUuid());
            reloading.remove(participant.playerUuid());
        } else if (event instanceof BattleRuntimeClosedEvent) {
            close();
        }
    }

    private void processReloads(long now) {
        for (var entry : List.copyOf(reloading.entrySet())) {
            Player player = Bukkit.getPlayer(entry.getKey());
            ReloadKey key = entry.getValue();
            if (player == null || !gateway.identify(
                player.getInventory().getItemInMainHand()
            ).filter(key.weaponId()::equals).isPresent()) {
                service.cancelReload(entry.getKey(), key.matchId(), key.weaponId());
                reloading.remove(entry.getKey());
                if (player != null) notice(player, "§e装填已取消");
            }
        }
        service.completeReloads(now);
        for (var entry : List.copyOf(reloading.entrySet())) {
            service.runtimeState(
                entry.getKey(), entry.getValue().matchId(), entry.getValue().weaponId()
            ).filter(value -> value.reloadState() == ReloadState.READY).ifPresent(value -> {
                reloading.remove(entry.getKey());
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player != null) notice(player, "§a装填完成");
            });
        }
    }

    private void updateDisplays(BattleRuntimeSnapshot battle) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Optional<WeaponId> weapon =
                gateway.identify(player.getInventory().getItemInMainHand());
            if (weapon.isEmpty() || !battle.available()) {
                clearFeedback(player.getUniqueId());
            } else {
                updateDisplay(player, weapon.get());
            }
        }
    }

    private void updateDisplay(Player player, WeaponId weaponId) {
        BattleRuntimeSnapshot battle = runtime.snapshot();
        if (!battle.available()) return;
        WeaponDefinition definition = service.definition(weaponId).orElse(null);
        if (definition == null) return;
        WeaponRuntimeState state = service.runtimeState(
            player.getUniqueId(), battle.matchId(), weaponId
        ).orElseGet(() -> new WeaponRuntimeState(
            player.getUniqueId(), battle.matchId(), weaponId,
            definition.ammo().magazineSize(), definition.ammo().reserveAmmo(),
            ReloadState.READY, 0, 0, 0, 0, 0
        ));
        updateFeedback(player, definition, state);
    }

    private WeaponDamagePolicy damagePolicy() {
        RegisteredServiceProvider<CombatPolicyService> registration =
            Bukkit.getServicesManager().getRegistration(CombatPolicyService.class);
        boolean friendlyFire = registration != null && registration.getProvider().friendlyFireEnabled();
        return new WeaponDamagePolicy(friendlyFire, configuration.core().allowSelfDamage());
    }

    private void shotFeedback(Player player, ShotResult result) {
        shotFeedback(player, result, DamageApplicationResult.NOT_APPLICABLE);
    }

    private void shotFeedback(Player player, ShotResult result, DamageApplicationResult damageResult) {
        WeaponFeedback.ShotFeedbackPresentation presentation = shotPresentation(result, damageResult);
        if (presentation == null) return;
        if (!submitFeedback(player, presentation.text(), presentation.deduplicationKey(),
            result.request().matchId(), java.util.OptionalLong.of(runtime.player(player.getUniqueId())
                .map(BattlePlayerSnapshot::lifeRevision).orElse(0L)))) {
            feedback.show(player, presentation);
        }
    }

    private WeaponFeedback.ShotFeedbackPresentation shotPresentation(ShotResult result, DamageApplicationResult damageResult) {
        String message = switch (result.outcome()) {
            case FIRED_BODY_HIT -> damageResult == DamageApplicationResult.APPLIED
                ? "§f命中" : damageBlockedMessage(damageResult);
            case FIRED_HEAD_HIT -> damageResult == DamageApplicationResult.APPLIED
                ? "§e爆头命中" : damageBlockedMessage(damageResult);
            case FRIENDLY_BLOCKED -> blockedShotMessage(result);
            case REJECTED_EMPTY -> "§c弹匣为空，按Q装填";
            case REJECTED_INTERNAL_ERROR -> "§c射击处理失败";
            default -> null;
        };
        if (message == null) return null;
        WeaponFeedback.FeedbackDelivery delivery = result.outcome() == ShotOutcome.FIRED_BODY_HIT
            || result.outcome() == ShotOutcome.FIRED_HEAD_HIT
            ? WeaponFeedback.FeedbackDelivery.ACTION_BAR : WeaponFeedback.FeedbackDelivery.NOTICE;
        String key = "weapon:" + result.outcome() + ":" + damageResult + ":" + result.relation();
        return new WeaponFeedback.ShotFeedbackPresentation(message, key, delivery);
    }

    private String blockedShotMessage(ShotResult result) {
        return switch (result.relation()) {
            case UNKNOWN -> "§e目标关系未知，伤害已阻止";
            case SELF -> "§e自身伤害已阻止";
            case SQUADMATE, TEAMMATE -> "§e友军伤害已阻止";
            default -> "§e伤害已阻止";
        };
    }

    private String damageBlockedMessage(DamageApplicationResult result) {
        return switch (result) {
            case BLOCKED_BY_SPAWN_PROTECTION -> "§e目标处于出生保护中";
            case CANCELLED_BY_EVENT -> "§e伤害已被服务器拦截";
            case NO_EFFECTIVE_DAMAGE -> "§e未造成有效伤害";
            case STALE_CONTEXT, TARGET_INVALID -> "§e目标状态已改变";
            case INTERNAL_FAILURE -> "§c伤害应用失败";
            default -> null;
        };
    }

    private void notice(Player player, String text) {
        BattleRuntimeSnapshot battle = runtime.snapshot();
        java.util.OptionalLong life = runtime.player(player.getUniqueId())
            .map(value -> java.util.OptionalLong.of(value.lifeRevision()))
            .orElse(java.util.OptionalLong.empty());
        if (!battle.available()
            || !submitFeedback(player, text, "weapon:notice", battle.matchId(), life)) {
            feedback.notice(player, text);
        }
    }

    private void updateFeedback(Player player, WeaponDefinition definition, WeaponRuntimeState state) {
        String text = definition.displayName() + "  " + state.magazineAmmo()
            + " / " + state.reserveAmmo()
            + (state.reloadState() == ReloadState.RELOADING ? "  RELOADING" : "");
        if (!submitFeedback(player, text, "weapon:state:" + definition.weaponId(),
            state.matchId(), java.util.OptionalLong.of(runtime.player(player.getUniqueId())
                .map(BattlePlayerSnapshot::lifeRevision).orElse(0L)))) {
            feedback.update(player, definition, state);
        }
    }

    private boolean submitFeedback(
        Player player,
        String text,
        String key,
        UUID matchId,
        java.util.OptionalLong lifeRevision
    ) {
        RegisteredServiceProvider<PlayerFeedbackService> registration =
            Bukkit.getServicesManager().getRegistration(PlayerFeedbackService.class);
        if (registration == null) return false;
        boolean submitted = registration.getProvider().submit(new FeedbackMessage(
            player.getUniqueId(), matchId, FeedbackChannel.WEAPON, FeedbackPriority.NORMAL,
            text, key, System.nanoTime(), System.nanoTime() + 1_500_000_000L, lifeRevision
        ));
        if (submitted) feedback.clear(player.getUniqueId());
        return submitted;
    }

    private void clearFeedback(UUID playerUuid) {
        try {
            feedback.clear(playerUuid);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "[warsim-weapons] Local weapon feedback clear failed.", exception);
        }
        try {
            RegisteredServiceProvider<PlayerFeedbackService> registration =
                Bukkit.getServicesManager().getRegistration(PlayerFeedbackService.class);
            if (registration != null) registration.getProvider().clear(playerUuid, FeedbackChannel.WEAPON);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "[warsim-weapons] Shared weapon feedback clear failed.", exception);
        }
    }

    private void clearAllFeedback() {
        try {
            feedback.clearAll();
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "[warsim-weapons] Local weapon feedback clearAll failed.", exception);
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                RegisteredServiceProvider<PlayerFeedbackService> registration =
                    Bukkit.getServicesManager().getRegistration(PlayerFeedbackService.class);
                if (registration != null) registration.getProvider().clear(player.getUniqueId(), FeedbackChannel.WEAPON);
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING, "[warsim-weapons] Shared weapon feedback clearAll failed.", exception);
            }
        }
    }

    private void reconcilePlayerInventory(Player player, String reason) {
        try {
            loadoutProvider.reconcilePlayerInventory(player);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING,
                "[warsim-weapons] Managed inventory reconcile failed during " + reason + ".", exception);
        }
    }

    private ShotResult rejected(
        Player player, WeaponId weapon, BattleRuntimeSnapshot battle,
        ShotOutcome outcome, WeaponFailureReason reason
    ) {
        UUID matchId = battle.matchId() == null ? new UUID(0, 0) : battle.matchId();
        return new ShotResult(
            new ShotRequest(
                ShotId.random(), matchId, battle.lifecycleRevision(),
                player.getUniqueId(), weapon, player.getWorld().getName(),
                new Vector3(0, 0, 0), new Vector3(0, 0, 1),
                System.nanoTime(), 0
            ),
            outcome, reason, HitResult.miss(), 0, null
        );
    }

    private void internalFailure(Player player, RuntimeException exception) {
        plugin.getLogger().log(Level.SEVERE, "[warsim-weapons] Shot processing failed.", exception);
        notice(player, "§c射击处理失败");
    }

    private static ShotOutcome map(WeaponFailureReason reason) {
        return switch (reason) {
            case COOLDOWN -> ShotOutcome.REJECTED_COOLDOWN;
            case EMPTY -> ShotOutcome.REJECTED_EMPTY;
            case RELOADING -> ShotOutcome.REJECTED_RELOADING;
            case INVALID_ITEM -> ShotOutcome.REJECTED_INVALID_ITEM;
            default -> ShotOutcome.REJECTED_INVALID_STATE;
        };
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        closeStep("listeners", () -> HandlerList.unregisterAll(this));
        closeStep("runtime subscription", runtimeSubscription::close);
        closeStep("reload tracking", reloading::clear);
        closeStep("damage adapter", damage::close);
        closeStep("damage attribution", attribution::close);
        closeStep("weapon feedback channels", this::clearAllFeedback);
        closeStep("loadout provider", loadoutProvider::close);
        closeStep("weapon service", service::close);
        closeStep("local feedback", feedback::close);
    }

    private void closeStep(String name, CloseStep step) {
        try {
            step.close();
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "[warsim-weapons] Failed to close " + name + ".", exception);
        }
    }

    private record ReloadKey(UUID matchId, WeaponId weaponId) {}

    @FunctionalInterface
    private interface CloseStep {
        void close() throws Exception;
    }
}
