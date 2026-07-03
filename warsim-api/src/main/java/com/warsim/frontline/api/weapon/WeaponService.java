package com.warsim.frontline.api.weapon;

import com.warsim.frontline.api.roster.CombatRelation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WeaponService extends AutoCloseable {
    WeaponSystemState state();
    List<WeaponDefinition> definitions();
    Optional<WeaponDefinition> definition(WeaponId weaponId);
    Optional<WeaponRuntimeState> runtimeState(UUID playerUuid, UUID matchId, WeaponId weaponId);
    WeaponOperationResult canFire(UUID playerUuid, UUID matchId, WeaponId weaponId, long nowNanos);
    Vector3 spreadDirection(WeaponId weaponId, Vector3 direction, long deterministicSeed);
    ShotResult fire(
        ShotContext context,
        java.util.function.Function<UUID, CombatRelation> relationResolver
    );
    default ShotResult fire(
        ShotContext context,
        java.util.function.Function<UUID, CombatRelation> relationResolver,
        WeaponDamagePolicy damagePolicy
    ) {
        return fire(context, relationResolver);
    }
    WeaponOperationResult startReload(UUID playerUuid, UUID matchId, WeaponId weaponId, long nowNanos);
    int completeReloads(long nowNanos);
    boolean cancelReload(UUID playerUuid, UUID matchId, WeaponId weaponId);
    default void clearWeapon(UUID playerUuid, UUID matchId, WeaponId weaponId) {
        clearPlayer(playerUuid);
    }
    void clearPlayer(UUID playerUuid);
    void clearMatch(UUID matchId);
    WeaponOperationResult refill(UUID playerUuid, UUID matchId, WeaponId weaponId);
    AutoCloseable subscribe(WeaponEventListener listener);
    void recordDamageApplied(ShotResult result);
    void recordKill(
        UUID matchId, ShotId shotId, WeaponId weaponId,
        UUID shooterUuid, UUID targetUuid
    );
    WeaponMetricsSnapshot metrics();
    @Override void close();
}
