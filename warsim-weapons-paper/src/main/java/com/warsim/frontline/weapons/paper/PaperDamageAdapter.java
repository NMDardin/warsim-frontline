package com.warsim.frontline.weapons.paper;

import com.warsim.frontline.api.battle.WarSimBattleRuntime;
import com.warsim.frontline.api.combat.CombatOutcomeService;
import com.warsim.frontline.api.combat.DamageCorrelationRequest;
import com.warsim.frontline.api.combat.DamageCorrelationResult;
import com.warsim.frontline.api.combat.DamageCorrelationToken;
import com.warsim.frontline.api.combat.SpawnProtectionService;
import com.warsim.frontline.api.match.MatchState;
import com.warsim.frontline.api.weapon.HitZone;
import com.warsim.frontline.api.weapon.ShotResult;
import com.warsim.frontline.weapons.DefaultWeaponService;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.RegisteredServiceProvider;

final class PaperDamageAdapter {
    static final String WEAPON_DAMAGE_METADATA = "warsim_weapon_damage";
    static final String WEAPON_DAMAGE_CALL_METADATA = "warsim_weapon_damage_call";
    private static final int MAX_PENDING_APPLICATIONS = 64;

    private final WarSimWeaponsPlugin plugin;
    private final WarSimBattleRuntime runtime;
    private final DamageAttributionRegistry attribution;
    private final DefaultWeaponService service;
    private final ArrayDeque<PendingDamageApplication> pendingApplications = new ArrayDeque<>();
    private final Set<UUID> markedEntities = new HashSet<>();

    PaperDamageAdapter(
        WarSimWeaponsPlugin plugin,
        WarSimBattleRuntime runtime,
        DamageAttributionRegistry attribution,
        DefaultWeaponService service
    ) {
        this.plugin = plugin;
        this.runtime = runtime;
        this.attribution = attribution;
        this.service = service;
    }

    DamageApplicationResult apply(ShotResult result) {
        if (!result.hit().hit() || result.requestedDamage() <= 0) return DamageApplicationResult.NOT_APPLICABLE;
        var battle = runtime.snapshot();
        if (!battle.available()
            || battle.matchState() != MatchState.PLAYING
            || !battle.matchId().equals(result.request().matchId())
            || battle.lifecycleRevision() != result.request().lifecycleRevision()) {
            service.recordStaleShot();
            return DamageApplicationResult.STALE_CONTEXT;
        }
        Player shooter = Bukkit.getPlayer(result.request().shooterUuid());
        Player target = Bukkit.getPlayer(result.hit().targetUuid());
        if (shooter == null || target == null || target.isDead()) return DamageApplicationResult.TARGET_INVALID;
        if (result.relation() == com.warsim.frontline.api.roster.CombatRelation.UNKNOWN) {
            return DamageApplicationResult.STALE_CONTEXT;
        }
        var shooterSnapshot = runtime.player(shooter.getUniqueId());
        var targetSnapshot = runtime.player(target.getUniqueId());
        if (shooterSnapshot.filter(value -> value.activeFor(battle.matchId())).isEmpty()
            || targetSnapshot.filter(value -> value.activeFor(battle.matchId())).isEmpty()) {
            service.recordStaleShot();
            return DamageApplicationResult.STALE_CONTEXT;
        }
        long now = System.nanoTime();
        spawnProtectionService().ifPresent(spawn ->
            spawn.removeOnAttack(shooter.getUniqueId(), battle.matchId(), shooterSnapshot.get().lifeRevision()));
        if (spawnProtectionService()
            .filter(spawn -> spawn.shouldBlockIncomingCombatDamage(
                target.getUniqueId(), battle.matchId(), targetSnapshot.get().lifeRevision()
            )).isPresent()) {
            return DamageApplicationResult.BLOCKED_BY_SPAWN_PROTECTION;
        }
        Optional<CombatOutcomeService> combatService = combatOutcomeService();
        DamageCorrelationResult correlation = combatService
            .map(combat -> combat.beginDamageCorrelation(new DamageCorrelationRequest(
                shooter.getUniqueId(),
                shooterSnapshot.get().lifeRevision(),
                target.getUniqueId(),
                targetSnapshot.get().lifeRevision(),
                battle.matchId(),
                battle.lifecycleRevision(),
                Optional.of(result.request().weaponId()),
                result.hit().hitZone() == HitZone.HEAD,
                result.hit().distance(),
                result.friendly(),
                now,
                combat.damageCorrelationTtlNanos()
            ))).orElse(DamageCorrelationResult.rejected("Combat service unavailable"));
        if (result.friendly() && !correlation.successful()) {
            return DamageApplicationResult.STALE_CONTEXT;
        }
        PendingDamageApplication pending = new PendingDamageApplication(
            shooter.getUniqueId(),
            target.getUniqueId(),
            battle.matchId(),
            battle.lifecycleRevision(),
            shooterSnapshot.get().lifeRevision(),
            targetSnapshot.get().lifeRevision(),
            result.requestedDamage(),
            survivability(target),
            correlation.successful() ? Optional.of(correlation.token()) : Optional.empty()
        );
        pendingApplications.addLast(pending);
        while (pendingApplications.size() > MAX_PENDING_APPLICATIONS) {
            PendingDamageApplication stale = pendingApplications.removeFirst();
            stale.cancel(combatOutcomeService(), "pending-overflow");
        }
        try {
            pushWeaponDamageMarker(shooter);
            pushWeaponDamageMarker(target);
            target.damage(result.requestedDamage(), shooter);
        } catch (RuntimeException exception) {
            pending.cancel(combatOutcomeService(), "damage-api-exception");
            throw exception;
        } finally {
            popWeaponDamageMarker(target);
            popWeaponDamageMarker(shooter);
            pendingApplications.remove(pending);
        }
        if (!pending.completed && !pending.cancelled) {
            pending.cancel(combatOutcomeService(), "no-matching-damage-event");
            return DamageApplicationResult.NO_EFFECTIVE_DAMAGE;
        }
        if (pending.result == DamageApplicationResult.APPLIED) {
            service.recordDamageApplied(result);
        }
        return pending.result;
    }

    void handleDamageEvent(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player shooter)
            || !(event.getEntity() instanceof Player target)) return;
        PendingDamageApplication pending = matching(shooter, target);
        if (pending == null) return;
        if (event.isCancelled()) {
            pending.cancel(combatOutcomeService(), "event-cancelled");
            pending.result = DamageApplicationResult.CANCELLED_BY_EVENT;
            return;
        }
        double finalDamage = event.getFinalDamage();
        double effectiveDamage = Math.min(Math.min(pending.beforeSurvivability, finalDamage), pending.requestedDamage);
        if (!Double.isFinite(effectiveDamage) || effectiveDamage <= 0.0) {
            pending.cancel(combatOutcomeService(), "zero-final-damage");
            pending.result = DamageApplicationResult.NO_EFFECTIVE_DAMAGE;
            return;
        }
        Optional<CombatOutcomeService> combat = combatOutcomeService();
        if (pending.token.isPresent() && combat.isPresent()
            && combat.get().completeDamageCorrelation(
                pending.token.get().correlationId(),
                effectiveDamage,
                System.nanoTime()
            )) {
            pending.completed = true;
            pending.result = DamageApplicationResult.APPLIED;
        } else {
            pending.cancel(combat, "correlation-complete-failed");
            pending.result = DamageApplicationResult.STALE_CONTEXT;
        }
    }

    private PendingDamageApplication matching(Player shooter, Player target) {
        for (Iterator<PendingDamageApplication> iterator = pendingApplications.descendingIterator(); iterator.hasNext();) {
            PendingDamageApplication pending = iterator.next();
            if (pending.shooterUuid.equals(shooter.getUniqueId()) && pending.targetUuid.equals(target.getUniqueId())) {
                return pending;
            }
        }
        return null;
    }

    private Optional<CombatOutcomeService> combatOutcomeService() {
        RegisteredServiceProvider<CombatOutcomeService> registration =
            Bukkit.getServicesManager().getRegistration(CombatOutcomeService.class);
        return registration == null ? Optional.empty() : Optional.of(registration.getProvider());
    }

    private Optional<SpawnProtectionService> spawnProtectionService() {
        RegisteredServiceProvider<SpawnProtectionService> registration =
            Bukkit.getServicesManager().getRegistration(SpawnProtectionService.class);
        return registration == null ? Optional.empty() : Optional.of(registration.getProvider());
    }

    private static double survivability(Player player) {
        double absorption;
        try {
            absorption = player.getAbsorptionAmount();
        } catch (NoSuchMethodError ignored) {
            absorption = 0.0;
        }
        return Math.max(0.0, player.getHealth()) + Math.max(0.0, absorption);
    }

    boolean isApplying(Player shooter, Player target) {
        return matching(shooter, target) != null;
    }

    void close() {
        Optional<CombatOutcomeService> combat = combatOutcomeService();
        for (PendingDamageApplication pending : List.copyOf(pendingApplications)) {
            pending.cancel(combat, "plugin-close");
        }
        pendingApplications.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (markedEntities.contains(player.getUniqueId())) {
                player.removeMetadata(WEAPON_DAMAGE_CALL_METADATA, plugin);
            }
        }
        markedEntities.clear();
    }

    private void pushWeaponDamageMarker(Entity entity) {
        int count = markerCount(entity).orElse(0);
        entity.setMetadata(WEAPON_DAMAGE_CALL_METADATA, new FixedMetadataValue(plugin, count + 1));
        markedEntities.add(entity.getUniqueId());
    }

    private void popWeaponDamageMarker(Entity entity) {
        Optional<Integer> count = markerCount(entity);
        if (count.isEmpty() || count.get() <= 1) {
            if (count.isEmpty() && entity.hasMetadata(WEAPON_DAMAGE_CALL_METADATA)) {
                plugin.getLogger().warning("[warsim-weapons] Invalid weapon damage metadata count; clearing marker.");
            }
            entity.removeMetadata(WEAPON_DAMAGE_CALL_METADATA, plugin);
            markedEntities.remove(entity.getUniqueId());
            return;
        }
        entity.setMetadata(WEAPON_DAMAGE_CALL_METADATA, new FixedMetadataValue(plugin, count.get() - 1));
    }

    private Optional<Integer> markerCount(Entity entity) {
        boolean invalidOwnedValue = false;
        for (MetadataValue value : entity.getMetadata(WEAPON_DAMAGE_CALL_METADATA)) {
            if (value.getOwningPlugin() != plugin) continue;
            Object raw = value.value();
            if (raw instanceof Integer count && count > 0) return Optional.of(count);
            if (raw instanceof Number number && number.intValue() > 0) return Optional.of(number.intValue());
            invalidOwnedValue = true;
        }
        if (invalidOwnedValue) return Optional.empty();
        return Optional.empty();
    }

    private static final class PendingDamageApplication {
        private final UUID shooterUuid;
        private final UUID targetUuid;
        private final UUID matchId;
        private final long lifecycleRevision;
        private final long shooterLifeRevision;
        private final long targetLifeRevision;
        private final double requestedDamage;
        private final double beforeSurvivability;
        private final Optional<DamageCorrelationToken> token;
        private boolean completed;
        private boolean cancelled;
        private DamageApplicationResult result = DamageApplicationResult.INTERNAL_FAILURE;

        private PendingDamageApplication(
            UUID shooterUuid,
            UUID targetUuid,
            UUID matchId,
            long lifecycleRevision,
            long shooterLifeRevision,
            long targetLifeRevision,
            double requestedDamage,
            double beforeSurvivability,
            Optional<DamageCorrelationToken> token
        ) {
            this.shooterUuid = shooterUuid;
            this.targetUuid = targetUuid;
            this.matchId = matchId;
            this.lifecycleRevision = lifecycleRevision;
            this.shooterLifeRevision = shooterLifeRevision;
            this.targetLifeRevision = targetLifeRevision;
            this.requestedDamage = requestedDamage;
            this.beforeSurvivability = beforeSurvivability;
            this.token = token;
        }

        private void cancel(Optional<CombatOutcomeService> combat, String reason) {
            if (completed || cancelled) return;
            cancelled = true;
            result = result == DamageApplicationResult.INTERNAL_FAILURE
                ? DamageApplicationResult.CANCELLED_BY_EVENT : result;
            token.ifPresent(value -> combat.ifPresent(service ->
                service.cancelDamageCorrelation(value.correlationId(), reason, System.nanoTime())));
        }
    }
}
