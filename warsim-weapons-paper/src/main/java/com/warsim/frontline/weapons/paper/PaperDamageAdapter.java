package com.warsim.frontline.weapons.paper;

import com.warsim.frontline.api.battle.WarSimBattleRuntime;
import com.warsim.frontline.api.combat.CombatOutcomeService;
import com.warsim.frontline.api.combat.DamageCorrelationRequest;
import com.warsim.frontline.api.combat.DamageCorrelationResult;
import com.warsim.frontline.api.combat.SpawnProtectionService;
import com.warsim.frontline.api.match.MatchState;
import com.warsim.frontline.api.weapon.HitZone;
import com.warsim.frontline.api.weapon.ShotResult;
import com.warsim.frontline.weapons.DefaultWeaponService;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

final class PaperDamageAdapter {
    private final WarSimBattleRuntime runtime;
    private final DamageAttributionRegistry attribution;
    private final DefaultWeaponService service;
    private boolean applying;
    private UUID applyingShooter;
    private UUID applyingTarget;

    PaperDamageAdapter(
        WarSimBattleRuntime runtime, DamageAttributionRegistry attribution,
        DefaultWeaponService service
    ) {
        this.runtime = runtime;
        this.attribution = attribution;
        this.service = service;
    }

    boolean apply(ShotResult result) {
        if (!result.hit().hit() || result.requestedDamage() <= 0) return false;
        var battle = runtime.snapshot();
        if (!battle.available()
            || battle.matchState() != MatchState.PLAYING
            || !battle.matchId().equals(result.request().matchId())
            || battle.lifecycleRevision() != result.request().lifecycleRevision()) {
            service.recordStaleShot();
            return false;
        }
        Player shooter = Bukkit.getPlayer(result.request().shooterUuid());
        Player target = Bukkit.getPlayer(result.hit().targetUuid());
        if (shooter == null || target == null || target.isDead()) return false;
        var shooterSnapshot = runtime.player(shooter.getUniqueId());
        var targetSnapshot = runtime.player(target.getUniqueId());
        if (shooterSnapshot.filter(value -> value.activeFor(battle.matchId())).isEmpty()
            || targetSnapshot.filter(value -> value.activeFor(battle.matchId())).isEmpty()) {
            service.recordStaleShot();
            return false;
        }
        long now = System.nanoTime();
        spawnProtectionService().ifPresent(spawn -> {
            if (shooterSnapshot.isPresent()) {
                spawn.removeOnAttack(shooter.getUniqueId(), battle.matchId(),
                    shooterSnapshot.get().lifeRevision());
            }
        });
        if (targetSnapshot.isPresent() && spawnProtectionService()
            .filter(spawn -> spawn.shouldBlockIncomingCombatDamage(
                target.getUniqueId(), battle.matchId(), targetSnapshot.get().lifeRevision()
            )).isPresent()) {
            return false;
        }
        double before = survivability(target);
        DamageCorrelationResult correlation = combatOutcomeService()
            .map(combat -> combat.beginDamageCorrelation(new DamageCorrelationRequest(
                shooter.getUniqueId(),
                shooterSnapshot.get().lifeRevision(),
                target.getUniqueId(),
                targetSnapshot.get().lifeRevision(),
                battle.matchId(),
                battle.lifecycleRevision(),
                result.request().weaponId(),
                result.hit().hitZone() == HitZone.HEAD,
                result.hit().distance(),
                false,
                now,
                2_000_000_000L
            ))).orElse(DamageCorrelationResult.rejected("Combat service unavailable"));
        applying = true;
        applyingShooter = shooter.getUniqueId();
        applyingTarget = target.getUniqueId();
        try {
            target.damage(result.requestedDamage(), shooter);
        } finally {
            applying = false;
            applyingShooter = null;
            applyingTarget = null;
        }
        double effectiveDamage = Math.min(
            Math.min(before, result.requestedDamage()),
            Math.max(0.0, before - survivability(target))
        );
        if (effectiveDamage <= 0) {
            if (correlation.successful()) {
                combatOutcomeService().ifPresent(combat ->
                    combat.cancelDamageCorrelation(correlation.token().correlationId(), "no-effective-damage", System.nanoTime())
                );
            }
            return false;
        }
        if (correlation.successful()) {
            combatOutcomeService().ifPresent(combat ->
                combat.completeDamageCorrelation(correlation.token().correlationId(), effectiveDamage, System.nanoTime())
            );
        }
        service.recordDamageApplied(result);
        return true;
    }

    private java.util.Optional<CombatOutcomeService> combatOutcomeService() {
        RegisteredServiceProvider<CombatOutcomeService> registration =
            Bukkit.getServicesManager().getRegistration(CombatOutcomeService.class);
        return registration == null ? java.util.Optional.empty()
            : java.util.Optional.of(registration.getProvider());
    }

    private java.util.Optional<SpawnProtectionService> spawnProtectionService() {
        RegisteredServiceProvider<SpawnProtectionService> registration =
            Bukkit.getServicesManager().getRegistration(SpawnProtectionService.class);
        return registration == null ? java.util.Optional.empty()
            : java.util.Optional.of(registration.getProvider());
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
        return applying
            && shooter.getUniqueId().equals(applyingShooter)
            && target.getUniqueId().equals(applyingTarget);
    }
}
