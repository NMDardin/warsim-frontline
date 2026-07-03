package com.warsim.frontline.match.combat;

import com.warsim.frontline.admin.WarSimCommandExtension;
import com.warsim.frontline.admin.WarSimCommandRegistry;
import com.warsim.frontline.api.battle.*;
import com.warsim.frontline.api.classes.CombatEligibilitySnapshot;
import com.warsim.frontline.api.classes.PlayerCombatState;
import com.warsim.frontline.api.combat.*;
import com.warsim.frontline.api.match.MatchState;
import com.warsim.frontline.api.roster.CombatRelation;
import com.warsim.frontline.api.roster.TeamAssignment;
import com.warsim.frontline.match.config.CombatPaperConfiguration;
import com.warsim.frontline.match.paper.PaperClassCoordinator;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

public final class CombatOutcomeCoordinator implements
    Listener,
    BattleRuntimeListener,
    CombatOutcomeService,
    CombatPolicyService,
    SpawnProtectionService,
    PlayerFeedbackService,
    AutoCloseable {
    private static final int MAX_CORRELATIONS = 2048;
    private static final int MAX_DEATH_KEYS = 2048;
    private final JavaPlugin plugin;
    private final WarSimBattleRuntime runtime;
    private final PaperClassCoordinator classCoordinator;
    private final CombatPaperConfiguration configuration;
    private final String configurationError;
    private final Map<UUID, MutableStats> statistics = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, PendingCorrelation> correlations = new LinkedHashMap<>();
    private final Map<TargetLifeKey, List<MutableContribution>> contributions = new LinkedHashMap<>();
    private final Map<TargetLifeKey, TimedDamageSource> lastEffectiveDamage = new LinkedHashMap<>();
    private final LinkedHashSet<TargetLifeKey> processedDeaths = new LinkedHashSet<>();
    private final Map<UUID, SpawnProtectionSnapshot> protections = new LinkedHashMap<>();
    private final Map<UUID, EnumMap<FeedbackChannel, FeedbackState>> feedback = new LinkedHashMap<>();
    private final Map<UUID, RenderedFeedback> renderedFeedback = new LinkedHashMap<>();
    private final Map<UUID, HudContext> hud = new LinkedHashMap<>();
    private final Set<UUID> hudDisabled = new HashSet<>();
    private final Set<UUID> killFeedDisabled = new HashSet<>();
    private final Map<UUID, Long> killFeedLastSent = new LinkedHashMap<>();
    private final ArrayDeque<KillFeedEntry> killFeed = new ArrayDeque<>();
    private final Metrics metrics = new Metrics();
    private final List<AutoCloseable> commandRegistrations = new ArrayList<>();
    private AutoCloseable runtimeSubscription;
    private boolean closed;
    private boolean enabled;
    private String lastError;
    private long tick;

    public CombatOutcomeCoordinator(
        JavaPlugin plugin,
        WarSimBattleRuntime runtime,
        PaperClassCoordinator classCoordinator,
        CombatPaperConfiguration configuration,
        String configurationError
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.classCoordinator = Objects.requireNonNull(classCoordinator, "classCoordinator");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.configurationError = configurationError;
        this.enabled = configuration.combatEnabled() && configurationError == null;
        this.lastError = configurationError;
    }

    public void start(WarSimCommandRegistry registry) {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        runtimeSubscription = runtime.subscribe(this);
        plugin.getServer().getServicesManager().register(
            CombatOutcomeService.class, this, plugin, ServicePriority.Normal
        );
        plugin.getServer().getServicesManager().register(
            CombatPolicyService.class, this, plugin, ServicePriority.Normal
        );
        plugin.getServer().getServicesManager().register(
            SpawnProtectionService.class, this, plugin, ServicePriority.Normal
        );
        plugin.getServer().getServicesManager().register(
            PlayerFeedbackService.class, this, plugin, ServicePriority.Normal
        );
        commandRegistrations.add(registry.register(new StatsCommand()));
        commandRegistrations.add(registry.register(new HudCommand()));
        commandRegistrations.add(registry.register(new KillFeedCommand()));
        commandRegistrations.add(registry.register(new CombatCommand()));
    }

    @Override
    public synchronized DamageCorrelationResult beginDamageCorrelation(DamageCorrelationRequest request) {
        if (closed || !enabled) return DamageCorrelationResult.rejected("Combat outcome unavailable");
        cleanupCorrelations(request.createdAtMonotonic());
        var attacker = runtime.player(request.attackerUuid());
        var target = runtime.player(request.targetUuid());
        if (attacker.isEmpty() || target.isEmpty()
            || !attacker.get().activeFor(request.matchId())
            || !target.get().activeFor(request.matchId())
            || attacker.get().lifeRevision() != request.attackerLifeRevision()
            || target.get().lifeRevision() != request.targetLifeRevision()
            || request.friendly() && !configuration.friendlyFireEnabled()) {
            return DamageCorrelationResult.rejected("Invalid or friendly damage correlation");
        }
        if (shouldBlockIncomingCombatDamage(
            request.targetUuid(), request.matchId(), request.targetLifeRevision()
        )) {
            metrics.protectedDamageCancelled.incrementAndGet();
            return DamageCorrelationResult.rejected("Target is spawn protected");
        }
        UUID correlationId = UUID.randomUUID();
        DamageCorrelationToken token = new DamageCorrelationToken(
            correlationId,
            request.attackerUuid(),
            request.attackerLifeRevision(),
            request.targetUuid(),
            request.targetLifeRevision(),
            request.matchId(),
            request.lifecycleRevision(),
            request.weaponId(),
            request.headshot(),
            request.distance(),
            request.friendly(),
            request.createdAtMonotonic(),
            request.createdAtMonotonic() + request.ttlNanos()
        );
        correlations.put(correlationId, new PendingCorrelation(token));
        evictOldest(correlations, MAX_CORRELATIONS);
        return new DamageCorrelationResult(true, "created", token);
    }

    @Override
    public synchronized boolean completeDamageCorrelation(
        UUID correlationId,
        double effectiveDamage,
        long completedAtMonotonic
    ) {
        PendingCorrelation pending = correlations.remove(correlationId);
        if (pending == null || pending.consumed || pending.token.expiresAtMonotonic() < completedAtMonotonic
            || !Double.isFinite(effectiveDamage) || effectiveDamage <= 0) {
            return false;
        }
        pending.consumed = true;
        DamageCorrelationToken token = pending.token;
        var attacker = runtime.player(token.attackerUuid());
        var target = runtime.player(token.targetUuid());
        if (attacker.isEmpty() || target.isEmpty()
            || !attacker.get().activeFor(token.matchId())
            || !target.get().activeFor(token.matchId())
            || attacker.get().lifeRevision() != token.attackerLifeRevision()
            || target.get().lifeRevision() != token.targetLifeRevision()) {
            metrics.staleLifeEventsRejected.incrementAndGet();
            return false;
        }
        recordDamage(token, effectiveDamage, completedAtMonotonic);
        return true;
    }

    @Override
    public synchronized boolean cancelDamageCorrelation(UUID correlationId, String reason, long cancelledAtMonotonic) {
        PendingCorrelation pending = correlations.remove(correlationId);
        if (pending == null || pending.consumed) return false;
        pending.consumed = true;
        return true;
    }

    @Override
    public long damageCorrelationTtlNanos() {
        return configuration.damageCorrelationTtlNanos();
    }

    @Override
    public boolean friendlyFireEnabled() {
        return configuration.friendlyFireEnabled();
    }

    @Override
    public synchronized Optional<PlayerCombatStatistics> statistics(UUID playerUuid) {
        MutableStats stats = statistics.get(playerUuid);
        return stats == null ? Optional.empty() : Optional.of(stats.snapshot());
    }

    @Override
    public synchronized CombatOutcomeSnapshot snapshot() {
        Map<UUID, PlayerCombatStatistics> stats = new LinkedHashMap<>();
        statistics.forEach((uuid, value) -> stats.put(uuid, value.snapshot()));
        BattleRuntimeSnapshot battle = runtime.snapshot();
        return new CombatOutcomeSnapshot(
            enabled,
            battle.matchId(),
            battle.lifecycleRevision(),
            stats,
            List.copyOf(killFeed),
            metrics.snapshot(),
            lastError
        );
    }

    @Override
    public synchronized void clearPlayer(UUID playerUuid) {
        statistics.remove(playerUuid);
        clearContributionsFor(playerUuid);
        killFeedLastSent.remove(playerUuid);
        feedback.remove(playerUuid);
        renderedFeedback.remove(playerUuid);
        metrics.combatStateCleanupCount.incrementAndGet();
    }

    @Override
    public synchronized void create(SpawnProtectionSnapshot protection) {
        if (closed || !configuration.spawnProtectionEnabled()) return;
        protections.put(protection.playerUuid(), protection);
        metrics.spawnProtectionsCreated.incrementAndGet();
    }

    @Override
    public synchronized Optional<SpawnProtectionSnapshot> snapshot(UUID playerUuid) {
        return Optional.ofNullable(protections.get(playerUuid));
    }

    @Override
    public synchronized boolean remove(
        UUID playerUuid,
        UUID matchId,
        long lifeRevision,
        SpawnProtectionRemovalReason reason
    ) {
        SpawnProtectionSnapshot protection = protections.get(playerUuid);
        if (protection == null || !protection.matchId().equals(matchId)
            || protection.lifeRevision() != lifeRevision) return false;
        if (reason == SpawnProtectionRemovalReason.ATTACK
            && !configuration.removeOnWeaponFire()) return false;
        if (reason == SpawnProtectionRemovalReason.MELEE_ATTACK
            && !configuration.removeOnMeleeAttack()) return false;
        if (reason == SpawnProtectionRemovalReason.OBJECTIVE_PRESENCE
            && !configuration.removeOnObjectivePresence()) return false;
        protections.remove(playerUuid);
        switch (reason) {
            case ATTACK, MELEE_ATTACK -> metrics.spawnProtectionsRemovedByAttack.incrementAndGet();
            case MOVEMENT, INVALID_POSITION -> metrics.spawnProtectionsRemovedByMovement.incrementAndGet();
            case OBJECTIVE_PRESENCE -> metrics.spawnProtectionsRemovedByObjective.incrementAndGet();
            case EXPIRED -> metrics.spawnProtectionsExpired.incrementAndGet();
            default -> {
            }
        }
        return true;
    }

    @Override
    public synchronized boolean removeOnAttack(UUID playerUuid, UUID matchId, long lifeRevision) {
        return remove(playerUuid, matchId, lifeRevision, SpawnProtectionRemovalReason.ATTACK);
    }

    @Override
    public synchronized boolean shouldBlockIncomingCombatDamage(UUID targetUuid, UUID matchId, long lifeRevision) {
        if (!configuration.blockIncomingCombatDamage()) return false;
        SpawnProtectionSnapshot protection = protections.get(targetUuid);
        return protection != null
            && protection.matchId().equals(matchId)
            && protection.lifeRevision() == lifeRevision;
    }

    @Override
    public long protectionDurationNanos() {
        return configuration.spawnProtectionDurationNanos();
    }

    @Override
    public synchronized boolean submit(FeedbackMessage message) {
        if (closed || !configuration.feedbackEnabled()) return false;
        var player = runtime.player(message.playerUuid());
        if (player.isEmpty()
            || !message.matchId().equals(player.get().matchId())
            || message.lifeRevision().isPresent()
                && player.get().lifeRevision() != message.lifeRevision().getAsLong()) {
            metrics.feedbackMessagesSuppressed.incrementAndGet();
            return false;
        }
        feedback.computeIfAbsent(message.playerUuid(), ignored -> new EnumMap<>(FeedbackChannel.class))
            .put(message.channel(), new FeedbackState(message));
        metrics.feedbackMessagesSubmitted.incrementAndGet();
        return true;
    }

    @Override
    public synchronized void clear(UUID playerUuid) {
        feedback.remove(playerUuid);
        renderedFeedback.remove(playerUuid);
    }

    @Override
    public synchronized void clear(UUID playerUuid, FeedbackChannel channel) {
        EnumMap<FeedbackChannel, FeedbackState> channels = feedback.get(playerUuid);
        if (channels != null) {
            channels.remove(channel);
            if (channels.isEmpty()) feedback.remove(playerUuid);
        }
        RenderedFeedback rendered = renderedFeedback.get(playerUuid);
        if (rendered != null && rendered.channel == channel) {
            renderedFeedback.remove(playerUuid);
        }
    }

    private synchronized void closeHud(UUID playerUuid) {
        HudContext context = hud.remove(playerUuid);
        if (context != null) context.close();
    }

    @Override
    public synchronized void clearMatch(UUID matchId) {
        feedback.clear();
        renderedFeedback.clear();
        for (HudContext context : hud.values()) context.close();
        hud.clear();
    }

    @Override
    public void onEvent(BattleRuntimeEvent event) {
        if (closed) return;
        if (event instanceof BattleTickEvent tickEvent) {
            tick = tickEvent.tick();
            synchronized (this) {
                tick(tickEvent.monotonicNanos());
            }
        } else if (event instanceof BattleMatchChangedEvent changed) {
            if (!Objects.equals(changed.previous().matchId(), changed.current().matchId())
                || changed.current().matchState() == MatchState.RESETTING
                || changed.current().matchState() == MatchState.STOPPING
                || changed.current().matchState() == MatchState.STOPPED
                || changed.current().matchState() == MatchState.FAILED) {
                synchronized (this) {
                    clearMatchState();
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCombatDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player target)
            || !(event.getDamager() instanceof Player attacker)) return;
        if (isWeaponDamageCall(event)) return;
        BattleRuntimeSnapshot battle = runtime.snapshot();
        if (!battle.available() || battle.matchState() != MatchState.PLAYING) return;
        var attackerSnapshot = runtime.player(attacker.getUniqueId());
        var targetSnapshot = runtime.player(target.getUniqueId());
        if (attackerSnapshot.isEmpty() || targetSnapshot.isEmpty()
            || !attackerSnapshot.get().activeFor(battle.matchId())
            || !targetSnapshot.get().activeFor(battle.matchId())) return;
        CombatRelation relation = runtime.relation(attacker.getUniqueId(), target.getUniqueId());
        boolean friendly = friendlyTeamRelation(relation);
        if (relation == CombatRelation.UNKNOWN || friendly && !configuration.friendlyFireEnabled()) {
            event.setCancelled(true);
            return;
        }
        remove(attacker.getUniqueId(), battle.matchId(), attackerSnapshot.get().lifeRevision(),
            SpawnProtectionRemovalReason.MELEE_ATTACK);
        if (shouldBlockIncomingCombatDamage(
            target.getUniqueId(), battle.matchId(), targetSnapshot.get().lifeRevision()
        )) {
            event.setCancelled(true);
            metrics.protectedDamageCancelled.incrementAndGet();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onMeleeDamageResolved(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player target)
            || !(event.getDamager() instanceof Player attacker)
            || isWeaponDamageCall(event)) return;
        if (event.isCancelled() || event.getFinalDamage() <= 0) return;
        BattleRuntimeSnapshot battle = runtime.snapshot();
        if (!battle.available() || battle.matchState() != MatchState.PLAYING) return;
        var attackerSnapshot = runtime.player(attacker.getUniqueId());
        var targetSnapshot = runtime.player(target.getUniqueId());
        if (attackerSnapshot.isEmpty() || targetSnapshot.isEmpty()
            || !attackerSnapshot.get().activeFor(battle.matchId())
            || !targetSnapshot.get().activeFor(battle.matchId())) return;
        CombatRelation relation = runtime.relation(attacker.getUniqueId(), target.getUniqueId());
        boolean friendly = friendlyTeamRelation(relation);
        if (relation == CombatRelation.UNKNOWN || friendly && !configuration.friendlyFireEnabled()) return;
        double before = Math.max(0.0, target.getHealth());
        double effective = Math.min(event.getFinalDamage(), before);
        if (effective <= 0) return;
        long now = System.nanoTime();
        DamageCorrelationResult correlation = beginDamageCorrelation(new DamageCorrelationRequest(
            attacker.getUniqueId(),
            attackerSnapshot.get().lifeRevision(),
            target.getUniqueId(),
            targetSnapshot.get().lifeRevision(),
            battle.matchId(),
            battle.lifecycleRevision(),
            Optional.empty(),
            false,
            attacker.getLocation().distance(target.getLocation()),
            friendly,
            now,
            configuration.damageCorrelationTtlNanos()
        ));
        if (correlation.successful()) {
            completeDamageCorrelation(correlation.token().correlationId(), effective, now);
        }
    }

    private boolean isWeaponDamageCall(EntityDamageByEntityEvent event) {
        return event.getEntity().hasMetadata("warsim_weapon_damage_call")
            || event.getDamager().hasMetadata("warsim_weapon_damage_call");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        processDeath(event.getPlayer(), event.getEntity().getLastDamageCause(), Instant.now(), System.nanoTime());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        synchronized (this) {
            feedback.remove(uuid);
            renderedFeedback.remove(uuid);
            hudDisabled.remove(uuid);
            killFeedDisabled.remove(uuid);
            HudContext context = hud.remove(uuid);
            if (context != null) context.close();
        }
    }

    private void processDeath(Player player, EntityDamageEvent cause, Instant now, long nowNanos) {
        BattleRuntimeSnapshot battle = runtime.snapshot();
        var playerSnapshot = runtime.player(player.getUniqueId());
        if (!battle.available() || playerSnapshot.isEmpty()
            || !playerSnapshot.get().matchId().equals(battle.matchId())) {
            return;
        }
        long lifeRevision = playerSnapshot.get().lifeRevision();
        TargetLifeKey deathKey = new TargetLifeKey(battle.matchId(), player.getUniqueId(), lifeRevision);
        boolean declared;
        synchronized (this) {
            declared = processedDeaths.add(deathKey);
            while (processedDeaths.size() > MAX_DEATH_KEYS) {
                processedDeaths.remove(processedDeaths.iterator().next());
            }
        }
        if (!declared) {
            metrics.duplicateDeathsRejected.incrementAndGet();
            return;
        }
        if (!enabled || playerSnapshot.get().combatState() != PlayerCombatState.ALIVE) {
            classCoordinator.handleCombatDeath(player, lifeRevision);
            return;
        }
        CombatDeathRecord record;
        synchronized (this) {
            record = settleDeath(player, battle, playerSnapshot.get(), cause, now, nowNanos);
            remove(player.getUniqueId(), battle.matchId(), lifeRevision, SpawnProtectionRemovalReason.DEATH);
            contributions.remove(deathKey);
            lastEffectiveDamage.remove(deathKey);
        }
        classCoordinator.handleCombatDeath(player, lifeRevision);
        if (record != null && configuration.killFeedEnabled()) {
            sendKillFeed(record);
        }
    }

    private CombatDeathRecord settleDeath(
        Player victim,
        BattleRuntimeSnapshot battle,
        BattlePlayerSnapshot victimSnapshot,
        EntityDamageEvent cause,
        Instant now,
        long nowNanos
    ) {
        TargetLifeKey key = new TargetLifeKey(battle.matchId(), victim.getUniqueId(), victimSnapshot.lifeRevision());
        CombatDamageSource source = effectiveSource(key, cause, nowNanos);
        CombatKillClassification classification = classify(victim.getUniqueId(), source, cause);
        List<CombatAssistRecord> assists = assists(key, source, nowNanos);
        MutableStats victimStats = stats(victim.getUniqueId(), battle.matchId());
        victimStats.deaths++;
        victimStats.damageReceived = Math.max(victimStats.damageReceived, victimStats.damageReceived);
        victimStats.currentKillStreak = 0;
        metrics.deathsRecorded.incrementAndGet();
        if (classification == CombatKillClassification.ENVIRONMENT) {
            victimStats.environmentalDeaths++;
            metrics.environmentalDeaths.incrementAndGet();
        } else if (classification == CombatKillClassification.SUICIDE) {
            victimStats.suicides++;
            metrics.suicides.incrementAndGet();
        }
        sourceAsKiller(source, victim.getUniqueId()).ifPresent(killer -> {
            MutableStats killerStats = stats(killer.attackerUuid(), battle.matchId());
            if (classification != CombatKillClassification.TEAM_KILL) {
                killerStats.kills++;
                killerStats.currentKillStreak++;
                killerStats.highestKillStreak = Math.max(killerStats.highestKillStreak, killerStats.currentKillStreak);
                metrics.killsRecorded.incrementAndGet();
            }
            killerStats.longestKillDistance = Math.max(killerStats.longestKillDistance, killer.distance());
            if (killer.headshot()) {
                killerStats.headshotKills++;
                metrics.headshotKills.incrementAndGet();
            }
            if (classification == CombatKillClassification.TEAM_KILL) {
                killerStats.teamKills++;
                metrics.teamKills.incrementAndGet();
            }
        });
        for (CombatAssistRecord assist : assists) {
            MutableStats assistStats = stats(assist.assisterUuid(), battle.matchId());
            assistStats.assists++;
            metrics.assistsRecorded.incrementAndGet();
        }
        CombatDeathRecord record = new CombatDeathRecord(
            battle.matchId(), battle.lifecycleRevision(), victim.getUniqueId(),
            victimSnapshot.lifeRevision(), Optional.ofNullable(source), classification,
            assists, now
        );
        createKillFeed(record, victim);
        return record;
    }

    private void recordDamage(DamageCorrelationToken token, double damage, long nowNanos) {
        TargetLifeKey key = new TargetLifeKey(token.matchId(), token.targetUuid(), token.targetLifeRevision());
        MutableStats attacker = stats(token.attackerUuid(), token.matchId());
        MutableStats target = stats(token.targetUuid(), token.matchId());
        attacker.damageDealt += damage;
        target.damageReceived += damage;
        CombatDamageType type = token.weaponId().isPresent()
            ? CombatDamageType.WEAPON : CombatDamageType.MELEE;
        CombatDamageSource source = new CombatDamageSource(
            token.attackerUuid(), token.attackerLifeRevision(), type,
            token.weaponId(), token.headshot(), token.friendly(), token.distance()
        );
        lastEffectiveDamage.put(key, new TimedDamageSource(
            source,
            nowNanos,
            nowNanos + configuration.attributionTtlNanos(),
            nowNanos + configuration.environmentalAttributionTtlNanos()
        ));
        List<MutableContribution> list = contributions.computeIfAbsent(key, ignored -> new ArrayList<>());
        MutableContribution existing = list.stream()
            .filter(value -> value.attackerUuid.equals(token.attackerUuid()))
            .findFirst().orElse(null);
        if (existing == null) {
            existing = new MutableContribution(token.attackerUuid(), token.attackerLifeRevision(),
                token.weaponId(), type, token.friendly());
            list.add(existing);
        }
        existing.accumulatedDamage += damage;
        existing.lastDamageAtMonotonic = nowNanos;
        existing.expiryAtMonotonic = nowNanos + configuration.attributionTtlNanos();
        existing.headshot |= token.headshot();
        evictContributors(list, nowNanos);
        metrics.damageContributionsRecorded.incrementAndGet();
    }

    private List<CombatAssistRecord> assists(TargetLifeKey key, CombatDamageSource killer, long nowNanos) {
        List<MutableContribution> list = contributions.getOrDefault(key, List.of());
        list.removeIf(value -> value.expiryAtMonotonic < nowNanos
            || runtime.player(value.attackerUuid)
                .filter(player -> player.activeFor(key.matchId)
                    && player.lifeRevision() == value.attackerLifeRevision)
                .isEmpty());
        double total = list.stream()
            .filter(value -> value.expiryAtMonotonic >= nowNanos)
            .mapToDouble(value -> value.accumulatedDamage).sum();
        ArrayList<CombatAssistRecord> result = new ArrayList<>();
        for (MutableContribution contribution : list) {
            if (contribution.expiryAtMonotonic < nowNanos
                || contribution.friendly
                || contribution.attackerUuid.equals(key.targetUuid)
                || killer != null && contribution.attackerUuid.equals(killer.attackerUuid())
                || !isEnemyContribution(contribution.attackerUuid, key.targetUuid)
                || contribution.accumulatedDamage < configuration.assistMinimumDamage()
                || total > 0 && contribution.accumulatedDamage / total < configuration.assistMinimumPercentage()
                || runtime.player(contribution.attackerUuid).isEmpty()) {
                continue;
            }
            result.add(new CombatAssistRecord(
                contribution.attackerUuid, contribution.attackerLifeRevision,
                contribution.accumulatedDamage, contribution.headshot
            ));
        }
        return List.copyOf(result);
    }

    private CombatKillClassification classify(UUID victim, CombatDamageSource source, EntityDamageEvent cause) {
        if (source == null) {
            return cause == null ? CombatKillClassification.UNKNOWN : CombatKillClassification.ENVIRONMENT;
        }
        if (source.attackerUuid().equals(victim)) return CombatKillClassification.SUICIDE;
        if (source.friendly()) return CombatKillClassification.TEAM_KILL;
        return source.headshot() ? CombatKillClassification.HEADSHOT_KILL : CombatKillClassification.ENEMY_KILL;
    }

    private CombatDamageSource effectiveSource(TargetLifeKey key, EntityDamageEvent cause, long nowNanos) {
        TimedDamageSource timed = lastEffectiveDamage.get(key);
        if (timed == null) return null;
        if (!sourceStillValid(key, timed.source())) {
            lastEffectiveDamage.remove(key);
            return null;
        }
        if (directPlayerCauseMatches(cause, timed.source()) && timed.expiresAtMonotonic() >= nowNanos) {
            return timed.source();
        }
        if (cause == null) return null;
        if (cause instanceof EntityDamageByEntityEvent byEntity
            && byEntity.getDamager() instanceof Player) {
            return null;
        }
        if (!configuration.environmentalAttributionEnabled()) return null;
        return timed.environmentalExpiresAtMonotonic() >= nowNanos ? timed.source() : null;
    }

    private boolean directPlayerCauseMatches(EntityDamageEvent cause, CombatDamageSource source) {
        if (!(cause instanceof EntityDamageByEntityEvent byEntity)
            || !(byEntity.getDamager() instanceof Player attacker)) return false;
        return attacker.getUniqueId().equals(source.attackerUuid());
    }

    private boolean sourceStillValid(TargetLifeKey key, CombatDamageSource source) {
        var attacker = runtime.player(source.attackerUuid());
        return attacker.isPresent()
            && attacker.get().activeFor(key.matchId)
            && attacker.get().lifeRevision() == source.attackerLifeRevision();
    }

    private Optional<CombatDamageSource> sourceAsKiller(CombatDamageSource source, UUID victim) {
        if (source == null || source.attackerUuid().equals(victim)) return Optional.empty();
        return source.friendly() && !configuration.friendlyFireEnabled() ? Optional.empty() : Optional.of(source);
    }

    private boolean isEnemyContribution(UUID attackerUuid, UUID targetUuid) {
        return runtime.relation(attackerUuid, targetUuid) == CombatRelation.ENEMY;
    }

    private static boolean friendlyTeamRelation(CombatRelation relation) {
        return relation == CombatRelation.SQUADMATE || relation == CombatRelation.TEAMMATE;
    }

    private void createKillFeed(CombatDeathRecord record, Player victim) {
        String killer = record.killer()
            .map(source -> Optional.ofNullable(Bukkit.getPlayer(source.attackerUuid()))
                .map(Player::getName).orElse("敌方"))
            .orElse(record.classification() == CombatKillClassification.ENVIRONMENT ? "环境" : "未知");
        String weapon = record.killer().flatMap(CombatDamageSource::weaponId)
            .map(Object::toString).orElse(record.classification().name());
        KillFeedEntry entry = new KillFeedEntry(
            safe(killer, 16), safe(victim.getName(), 16), safe(weapon, 32),
            record.classification() == CombatKillClassification.HEADSHOT_KILL,
            record.classification() == CombatKillClassification.TEAM_KILL,
            record.killer().map(CombatDamageSource::distance).orElse(0.0),
            record.occurredAt(),
            System.nanoTime() + configuration.killFeedTtlNanos()
        );
        killFeed.addLast(entry);
        while (killFeed.size() > configuration.killFeedMaximumEntries()) killFeed.removeFirst();
        metrics.killFeedEntriesCreated.incrementAndGet();
    }
    private void sendKillFeed(CombatDeathRecord record) {
        long now = System.nanoTime();
        if (killFeed.isEmpty()) return;
        KillFeedEntry entry = killFeed.getLast();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (killFeedDisabled.contains(player.getUniqueId())
                || runtime.player(player.getUniqueId())
                    .filter(snapshot -> snapshot.activeFor(runtime.snapshot().matchId()))
                    .isEmpty()) {
                continue;
            }
            long previous = killFeedLastSent.getOrDefault(player.getUniqueId(), 0L);
            if (now - previous < configuration.killFeedThrottleNanos()) continue;
            killFeedLastSent.put(player.getUniqueId(), now);
            player.sendMessage("§7[战况] §f" + entry.killerDisplayName() + " §7击败 §f"
                + entry.victimDisplayName() + (entry.headshot() ? " §e爆头" : ""));
        }
    }
    private void tick(long nowNanos) {
        cleanupCorrelations(nowNanos);
        cleanupDamageSources(nowNanos);
        cleanupProtections(nowNanos);
        cleanupKillFeed(nowNanos);
        renderFeedback(nowNanos);
        if (configuration.hudEnabled() && tick % configuration.hudUpdateIntervalTicks() == 0) {
            updateHud(nowNanos);
        }
    }

    private void cleanupCorrelations(long nowNanos) {
        correlations.entrySet().removeIf(entry -> entry.getValue().token.expiresAtMonotonic() < nowNanos);
    }

    private void cleanupDamageSources(long nowNanos) {
        lastEffectiveDamage.entrySet().removeIf(entry ->
            entry.getValue().expiresAtMonotonic() < nowNanos
                && entry.getValue().environmentalExpiresAtMonotonic() < nowNanos);
        contributions.entrySet().removeIf(entry -> {
            entry.getValue().removeIf(value -> value.expiryAtMonotonic < nowNanos);
            return entry.getValue().isEmpty();
        });
    }

    private void cleanupProtections(long nowNanos) {
        for (SpawnProtectionSnapshot protection : List.copyOf(protections.values())) {
            if (protection.expiresAtMonotonic() <= nowNanos) {
                remove(protection.playerUuid(), protection.matchId(), protection.lifeRevision(),
                    SpawnProtectionRemovalReason.EXPIRED);
                continue;
            }
            if (configuration.removeOnLeaveRadius()) {
                Player player = Bukkit.getPlayer(protection.playerUuid());
                if (player == null) continue;
                Location location = player.getLocation();
                SpawnPositionSnapshot spawn = protection.spawnPosition();
                if (location.getWorld() == null
                    || !location.getWorld().getName().equals(spawn.worldName())
                    || !Double.isFinite(location.getX())
                    || location.distanceSquared(new Location(
                        location.getWorld(), spawn.x(), spawn.y(), spawn.z()
                    )) > configuration.protectedRadius() * configuration.protectedRadius()) {
                    remove(protection.playerUuid(), protection.matchId(), protection.lifeRevision(),
                        SpawnProtectionRemovalReason.MOVEMENT);
                }
            }
        }
    }

    private void cleanupKillFeed(long nowNanos) {
        killFeed.removeIf(entry -> entry.expiresAtMonotonic() < nowNanos);
    }

    private void renderFeedback(long nowNanos) {
        for (UUID playerUuid : List.copyOf(feedback.keySet())) {
            EnumMap<FeedbackChannel, FeedbackState> channels = feedback.get(playerUuid);
            if (channels == null) continue;
            channels.entrySet().removeIf(entry -> entry.getValue().message.expiresAtMonotonic() < nowNanos);
            FeedbackState selected = channels.values().stream()
                .max(Comparator.comparingInt(value -> priority(value.message)))
                .orElse(null);
            Player player = Bukkit.getPlayer(playerUuid);
            if (player == null) continue;
            if (selected == null) {
                if (channels.isEmpty()) feedback.remove(playerUuid);
                renderedFeedback.remove(playerUuid);
                continue;
            }
            RenderedFeedback rendered = renderedFeedback.get(playerUuid);
            if (rendered == null
                || rendered.channel != selected.message.channel()
                || !Objects.equals(rendered.key, selected.message.deduplicationKey())
                || !Objects.equals(rendered.content, selected.message.content())) {
                player.sendActionBar(Component.text(selected.message.content()));
                renderedFeedback.put(playerUuid, new RenderedFeedback(
                    selected.message.channel(),
                    selected.message.deduplicationKey(),
                    selected.message.content()
                ));
            }
        }
    }

    private void updateHud(long nowNanos) {
        BattleRuntimeSnapshot battle = runtime.snapshot();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (hudDisabled.contains(player.getUniqueId())) {
                continue;
            }
            if (player.getGameMode() == GameMode.SPECTATOR && runtime.player(player.getUniqueId()).isEmpty()) {
                continue;
            }
            HudContext context = hud.computeIfAbsent(player.getUniqueId(), uuid -> new HudContext(player));
            context.update(player, hudLines(player, battle));
        }
    }

    private List<String> hudLines(Player player, BattleRuntimeSnapshot battle) {
        var playerSnapshot = runtime.player(player.getUniqueId());
        PlayerCombatStatistics stats = statistics(player.getUniqueId())
            .orElse(PlayerCombatStatistics.empty(player.getUniqueId(), battle.matchId()));
        ArrayList<String> lines = new ArrayList<>();
        lines.add("§6WarSim Frontline");
        lines.add("§fMatch: §a" + (battle.matchState() == null ? "N/A" : battle.matchState()));
        lines.add("§fTime: §a--:--");
        lines.add("§fTeam: §a" + playerSnapshot.flatMap(p -> p.assignment().map(a -> a.teamSide().name())).orElse("无"));
        lines.add("§fSquad: §a" + playerSnapshot.flatMap(p -> p.assignment().flatMap(TeamAssignment::squadId).map(Object::toString)).orElse("无"));
        lines.add("§fClass: §a" + classCoordinator.service().selection(player.getUniqueId())
            .flatMap(value -> value.currentClass().map(Object::toString)).orElse("未选择"));
        lines.add("§fState: §a" + playerSnapshot.map(p -> p.combatState().name()).orElse("N/A"));
        lines.add("§fK/D/A: §a" + stats.kills() + "/" + stats.deaths() + "/" + stats.assists());
        lines.add("§fStreak: §a" + stats.currentKillStreak());
        snapshot(player.getUniqueId()).ifPresent(protection ->
            lines.add("§f保护: §a" + Math.max(0, (protection.expiresAtMonotonic() - System.nanoTime()) / 1_000_000_000L) + "s"));
        return lines;
    }
    private int priority(FeedbackMessage message) {
        int channel = switch (message.channel()) {
            case CRITICAL -> 600;
            case DEPLOYMENT -> 500;
            case COMBAT -> 400;
            case WEAPON -> 300;
            case OBJECTIVE -> 200;
            case SYSTEM -> 100;
        };
        int priority = switch (message.priority()) {
            case CRITICAL -> 30;
            case HIGH -> 20;
            case NORMAL -> 10;
            case LOW -> 0;
        };
        return channel + priority;
    }

    private MutableStats stats(UUID playerUuid, UUID matchId) {
        return statistics.computeIfAbsent(playerUuid, uuid -> new MutableStats(playerUuid, matchId));
    }

    private void evictContributors(List<MutableContribution> list, long nowNanos) {
        while (list.size() > configuration.maximumContributorsPerTarget()) {
            MutableContribution evicted = list.stream()
                .min(Comparator
                    .comparing((MutableContribution value) -> value.expiryAtMonotonic >= nowNanos)
                    .thenComparingDouble(value -> value.accumulatedDamage)
                    .thenComparingLong(value -> value.lastDamageAtMonotonic)
                    .thenComparing(value -> value.attackerUuid))
                .orElse(null);
            if (evicted == null) break;
            list.remove(evicted);
            metrics.contributionEvictions.incrementAndGet();
        }
    }

    private void clearContributionsFor(UUID playerUuid) {
        contributions.entrySet().removeIf(entry -> {
            if (entry.getKey().targetUuid.equals(playerUuid)) return true;
            entry.getValue().removeIf(value -> value.attackerUuid.equals(playerUuid));
            return entry.getValue().isEmpty();
        });
        lastEffectiveDamage.entrySet().removeIf(entry -> entry.getKey().targetUuid.equals(playerUuid)
            || entry.getValue().source().attackerUuid().equals(playerUuid));
    }

    private void clearMatchState() {
        correlations.clear();
        contributions.clear();
        lastEffectiveDamage.clear();
        protections.clear();
        feedback.clear();
        renderedFeedback.clear();
        killFeed.clear();
        killFeedLastSent.clear();
        hudDisabled.clear();
        killFeedDisabled.clear();
        statistics.clear();
        for (HudContext context : hud.values()) context.close();
        hud.clear();
    }

    private static <K, V> void evictOldest(LinkedHashMap<K, V> map, int maximum) {
        while (map.size() > maximum) map.remove(map.keySet().iterator().next());
    }

    private static String safe(String value, int maximum) {
        String cleaned = (value == null ? "未知" : value)
            .replaceAll("§.", "")
            .replaceAll("[\\p{Cntrl}\\n\\r]", "")
            .strip();
        return cleaned.length() <= maximum ? cleaned : cleaned.substring(0, maximum);
    }

    public List<String> statusLines() {
        CombatOutcomeSnapshot snapshot = snapshot();
        ArrayList<String> lines = new ArrayList<>();
        lines.add("§6WarSim Combat");
        lines.add("§fCombat: §a" + (enabled ? "ACTIVE" : "DISABLED"));
        lines.add("§fHUD: §a" + configuration.hudEnabled());
        lines.add("§fKillFeed: §a" + configuration.killFeedEnabled());
        lines.add("§fPlayers: §a" + snapshot.statistics().size());
        lines.add("§fKills/Deaths/Assists: §a" + snapshot.metrics().killsRecorded()
            + "/" + snapshot.metrics().deathsRecorded() + "/" + snapshot.metrics().assistsRecorded());
        if (lastError != null) lines.add("§cError: " + lastError);
        return List.copyOf(lines);
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        HandlerList.unregisterAll(this);
        commandRegistrations.forEach(resource -> {
            try { resource.close(); } catch (Exception ignored) {}
        });
        commandRegistrations.clear();
        try {
            plugin.getServer().getServicesManager().unregister(CombatOutcomeService.class, this);
            plugin.getServer().getServicesManager().unregister(CombatPolicyService.class, this);
            plugin.getServer().getServicesManager().unregister(SpawnProtectionService.class, this);
            plugin.getServer().getServicesManager().unregister(PlayerFeedbackService.class, this);
        } catch (RuntimeException ignored) {
        }
        if (runtimeSubscription != null) {
            try { runtimeSubscription.close(); } catch (Exception ignored) {}
        }
        clearMatchState();
    }

    private final class StatsCommand implements WarSimCommandExtension {
        @Override public String name() { return "stats"; }
        @Override public boolean execute(CommandSender sender, String[] arguments) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§c该命令只能由玩家执行。");
                return true;
            }
            if (!sender.hasPermission("warsim.player.stats")) {
                sender.sendMessage("§c你没有权限执行该命令。");
                return true;
            }
            PlayerCombatStatistics stats = statistics(player.getUniqueId())
                .orElse(PlayerCombatStatistics.empty(player.getUniqueId(), runtime.snapshot().matchId()));
            sender.sendMessage("§6局内统计 §fK/D/A: §a" + stats.kills() + "/"
                + stats.deaths() + "/" + stats.assists());
            sender.sendMessage("§f伤害: §a" + Math.round(stats.damageDealt())
                + " §f承伤: §a" + Math.round(stats.damageReceived()));
            return true;
        }
    }

    private final class HudCommand implements WarSimCommandExtension {
        @Override public String name() { return "hud"; }
        @Override public boolean execute(CommandSender sender, String[] arguments) {
            if (arguments.length == 1 && "status".equalsIgnoreCase(arguments[0])) {
                if (!sender.hasPermission("warsim.player.hud.status")) {
                    sender.sendMessage("§c你没有权限执行该命令。");
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§c该命令只能由玩家执行。");
                    return true;
                }
                HudContext context = hud.get(player.getUniqueId());
                sender.sendMessage("§6HUD状态：§a" + (context == null ? HudOwnershipState.AVAILABLE : context.state));
                return true;
            }
            if (arguments.length == 1 && ("on".equalsIgnoreCase(arguments[0]) || "off".equalsIgnoreCase(arguments[0]))) {
                if (!sender.hasPermission("warsim.player.hud.toggle")) {
                    sender.sendMessage("§c你没有权限执行该命令。");
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§c该命令只能由玩家执行。");
                    return true;
                }
                if ("off".equalsIgnoreCase(arguments[0])) {
                    hudDisabled.add(player.getUniqueId());
                    closeHud(player.getUniqueId());
                } else {
                    hudDisabled.remove(player.getUniqueId());
                    hud.computeIfAbsent(player.getUniqueId(), ignored -> new HudContext(player));
                }
                sender.sendMessage("§aHUD偏好已更新（仅当前连接生命周期）。");
                return true;
            }
            if (arguments.length == 2 && "refresh".equalsIgnoreCase(arguments[0])) {
                if (!sender.hasPermission("warsim.admin.hud.refresh")) {
                    sender.sendMessage("§c你没有权限执行该命令。");
                    return true;
                }
                if ("all".equalsIgnoreCase(arguments[1])) {
                    for (HudContext context : hud.values()) context.forceRefresh();
                    sender.sendMessage("§a已请求刷新全部WarSim HUD。");
                } else {
                    Player target = Bukkit.getPlayerExact(arguments[1]);
                    if (target == null) sender.sendMessage("§c玩家不在线。");
                    else if (hudDisabled.contains(target.getUniqueId())) {
                        sender.sendMessage("§e该玩家已关闭HUD，刷新不会重新开启。");
                    } else {
                        hud.computeIfAbsent(target.getUniqueId(), ignored -> new HudContext(target)).forceRefresh();
                        sender.sendMessage("§a已请求刷新玩家HUD。");
                    }
                }
                return true;
            }
            sender.sendMessage("§e用法：/warsim hud <status|on|off|refresh>");
            return true;
        }
    }

    private final class KillFeedCommand implements WarSimCommandExtension {
        @Override public String name() { return "killfeed"; }
        @Override public boolean execute(CommandSender sender, String[] arguments) {
            if (arguments.length == 1 && ("on".equalsIgnoreCase(arguments[0]) || "off".equalsIgnoreCase(arguments[0]))) {
                if (!sender.hasPermission("warsim.player.killfeed.toggle")) {
                    sender.sendMessage("§c你没有权限执行该命令。");
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§c该命令只能由玩家执行。");
                    return true;
                }
                if ("off".equalsIgnoreCase(arguments[0])) {
                    killFeedDisabled.add(player.getUniqueId());
                } else {
                    killFeedDisabled.remove(player.getUniqueId());
                }
                sender.sendMessage("§aKillFeed偏好已更新（仅当前连接生命周期）。");
                return true;
            }
            sender.sendMessage("§e用法：/warsim killfeed <on|off>");
            return true;
        }
    }

    private final class CombatCommand implements WarSimCommandExtension {
        @Override public String name() { return "combat"; }
        @Override public boolean execute(CommandSender sender, String[] arguments) {
            if (arguments.length >= 1 && "status".equalsIgnoreCase(arguments[0])) {
                if (!sender.hasPermission("warsim.admin.combat.status")) {
                    sender.sendMessage("§c你没有权限执行该命令。");
                    return true;
                }
                if (arguments.length == 1) statusLines().forEach(sender::sendMessage);
                else {
                    Player target = Bukkit.getPlayerExact(arguments[1]);
                    if (target == null) sender.sendMessage("§c玩家不在线。");
                    else statistics(target.getUniqueId())
                        .map(Object::toString).ifPresentOrElse(sender::sendMessage,
                            () -> sender.sendMessage("§e该玩家暂无局内统计。"));
                }
                return true;
            }
            if (arguments.length == 2 && "clear".equalsIgnoreCase(arguments[0])) {
                if (!sender.hasPermission("warsim.admin.combat.clear")) {
                    sender.sendMessage("§c你没有权限执行该命令。");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(arguments[1]);
                if (target == null) sender.sendMessage("§c玩家不在线。");
                else {
                    clearPlayer(target.getUniqueId());
                    sender.sendMessage("§a已清理该玩家局内统计和未结算贡献。");
                }
                return true;
            }
            if (arguments.length >= 2 && "protection".equalsIgnoreCase(arguments[0])) {
                Player target = Bukkit.getPlayerExact(arguments[1]);
                if (target == null) {
                    sender.sendMessage("§c玩家不在线。");
                    return true;
                }
                if (arguments.length == 2) {
                    if (!sender.hasPermission("warsim.admin.combat.protection")) {
                        sender.sendMessage("§c你没有权限执行该命令。");
                        return true;
                    }
                    sender.sendMessage(snapshot(target.getUniqueId()).map(Object::toString)
                        .orElse("§e该玩家没有出生保护。"));
                    return true;
                }
                if (arguments.length == 3 && "clear".equalsIgnoreCase(arguments[2])) {
                    if (!sender.hasPermission("warsim.admin.combat.protection.clear")) {
                        sender.sendMessage("§c你没有权限执行该命令。");
                        return true;
                    }
                    var player = runtime.player(target.getUniqueId());
                    if (player.isPresent()) {
                        remove(target.getUniqueId(), player.get().matchId(), player.get().lifeRevision(),
                            SpawnProtectionRemovalReason.ADMIN_CLEAR);
                    }
                    sender.sendMessage("§a已清理出生保护。");
                    return true;
                }
            }
            sender.sendMessage("§e用法：/warsim combat <status|clear|protection>");
            return true;
        }
    }

    private record TargetLifeKey(UUID matchId, UUID targetUuid, long lifeRevision) {}
    private record TimedDamageSource(
        CombatDamageSource source,
        long recordedAtMonotonic,
        long expiresAtMonotonic,
        long environmentalExpiresAtMonotonic
    ) {}
    private record RenderedFeedback(FeedbackChannel channel, String key, String content) {}
    private static final class MutableContribution {
        private final UUID attackerUuid;
        private final long attackerLifeRevision;
        private final Optional<com.warsim.frontline.api.weapon.WeaponId> weaponId;
        private final CombatDamageType type;
        private final boolean friendly;
        private double accumulatedDamage;
        private long lastDamageAtMonotonic;
        private long expiryAtMonotonic;
        private boolean headshot;
        private MutableContribution(UUID attackerUuid, long attackerLifeRevision,
            Optional<com.warsim.frontline.api.weapon.WeaponId> weaponId, CombatDamageType type, boolean friendly) {
            this.attackerUuid = attackerUuid;
            this.attackerLifeRevision = attackerLifeRevision;
            this.weaponId = weaponId == null ? Optional.empty() : weaponId;
            this.type = type;
            this.friendly = friendly;
        }
    }
    private static final class MutableStats {
        private final UUID playerUuid;
        private final UUID matchId;
        private int kills, deaths, assists, headshotKills, teamKills, suicides, environmentalDeaths;
        private double damageDealt, damageReceived, longestKillDistance;
        private int currentKillStreak, highestKillStreak;
        private MutableStats(UUID playerUuid, UUID matchId) {
            this.playerUuid = playerUuid;
            this.matchId = matchId;
        }
        private PlayerCombatStatistics snapshot() {
            return new PlayerCombatStatistics(playerUuid, matchId, kills, deaths, assists,
                headshotKills, teamKills, suicides, environmentalDeaths, damageDealt,
                damageReceived, longestKillDistance, currentKillStreak, highestKillStreak);
        }
    }
    private static final class PendingCorrelation {
        private final DamageCorrelationToken token;
        private boolean consumed;
        private PendingCorrelation(DamageCorrelationToken token) { this.token = token; }
    }
    private static final class FeedbackState {
        private final FeedbackMessage message;
        private FeedbackState(FeedbackMessage message) { this.message = message; }
    }
    private final class HudContext {
        private final Scoreboard previous;
        private Scoreboard board;
        private Objective objective;
        private HudOwnershipState state = HudOwnershipState.AVAILABLE;
        private List<String> lastLines = List.of();
        private HudContext(Player player) {
            previous = player.getScoreboard();
        }
        private void update(Player player, List<String> lines) {
            if (state == HudOwnershipState.BLOCKED_BY_FOREIGN_SCOREBOARD) return;
            if (board == null) {
                if (player.getScoreboard() != previous) {
                    state = HudOwnershipState.BLOCKED_BY_FOREIGN_SCOREBOARD;
                    return;
                }
                board = Objects.requireNonNull(Bukkit.getScoreboardManager()).getNewScoreboard();
                objective = board.registerNewObjective("warsim_hud", Criteria.DUMMY, Component.text("WarSim"));
                objective.setDisplaySlot(DisplaySlot.SIDEBAR);
                player.setScoreboard(board);
                state = HudOwnershipState.OWNED;
            }
            if (player.getScoreboard() != board) {
                state = HudOwnershipState.BLOCKED_BY_FOREIGN_SCOREBOARD;
                return;
            }
            if (lastLines.equals(lines)) {
                metrics.hudUpdatesDeduplicated.incrementAndGet();
                return;
            }
            for (String entry : board.getEntries()) board.resetScores(entry);
            int score = Math.min(15, lines.size());
            for (String line : lines.subList(0, score)) {
                objective.getScore(line.length() > 40 ? line.substring(0, 40) : line).setScore(score--);
            }
            lastLines = List.copyOf(lines);
            metrics.hudUpdates.incrementAndGet();
        }
        private void forceRefresh() {
            lastLines = List.of();
            if (state != HudOwnershipState.BLOCKED_BY_FOREIGN_SCOREBOARD) {
                state = HudOwnershipState.AVAILABLE;
            }
        }
        private void close() {
            if (board != null) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.getScoreboard() == board && previous != null) player.setScoreboard(previous);
                }
            }
            state = HudOwnershipState.CLOSED;
        }
    }
    private static final class Metrics {
        private final AtomicLong damageContributionsRecorded = new AtomicLong();
        private final AtomicLong contributionEvictions = new AtomicLong();
        private final AtomicLong killsRecorded = new AtomicLong();
        private final AtomicLong deathsRecorded = new AtomicLong();
        private final AtomicLong assistsRecorded = new AtomicLong();
        private final AtomicLong headshotKills = new AtomicLong();
        private final AtomicLong teamKills = new AtomicLong();
        private final AtomicLong suicides = new AtomicLong();
        private final AtomicLong environmentalDeaths = new AtomicLong();
        private final AtomicLong duplicateDeathsRejected = new AtomicLong();
        private final AtomicLong staleLifeEventsRejected = new AtomicLong();
        private final AtomicLong spawnProtectionsCreated = new AtomicLong();
        private final AtomicLong spawnProtectionsExpired = new AtomicLong();
        private final AtomicLong spawnProtectionsRemovedByAttack = new AtomicLong();
        private final AtomicLong spawnProtectionsRemovedByMovement = new AtomicLong();
        private final AtomicLong spawnProtectionsRemovedByObjective = new AtomicLong();
        private final AtomicLong protectedDamageCancelled = new AtomicLong();
        private final AtomicLong hudUpdates = new AtomicLong();
        private final AtomicLong hudUpdatesDeduplicated = new AtomicLong();
        private final AtomicLong feedbackMessagesSubmitted = new AtomicLong();
        private final AtomicLong feedbackMessagesSuppressed = new AtomicLong();
        private final AtomicLong killFeedEntriesCreated = new AtomicLong();
        private final AtomicLong combatStateCleanupCount = new AtomicLong();
        private final AtomicLong listenerFailures = new AtomicLong();
        private CombatOutcomeMetricsSnapshot snapshot() {
            return new CombatOutcomeMetricsSnapshot(
                damageContributionsRecorded.get(), contributionEvictions.get(),
                killsRecorded.get(), deathsRecorded.get(), assistsRecorded.get(),
                headshotKills.get(), teamKills.get(), suicides.get(), environmentalDeaths.get(),
                duplicateDeathsRejected.get(), staleLifeEventsRejected.get(),
                spawnProtectionsCreated.get(), spawnProtectionsExpired.get(),
                spawnProtectionsRemovedByAttack.get(), spawnProtectionsRemovedByMovement.get(),
                spawnProtectionsRemovedByObjective.get(), protectedDamageCancelled.get(),
                hudUpdates.get(), hudUpdatesDeduplicated.get(), feedbackMessagesSubmitted.get(),
                feedbackMessagesSuppressed.get(), killFeedEntriesCreated.get(),
                combatStateCleanupCount.get(), listenerFailures.get()
            );
        }
    }
}
