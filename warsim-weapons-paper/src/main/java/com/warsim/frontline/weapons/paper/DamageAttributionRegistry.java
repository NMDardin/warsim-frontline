package com.warsim.frontline.weapons.paper;

import com.warsim.frontline.api.weapon.ShotId;
import com.warsim.frontline.api.weapon.WeaponId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

final class DamageAttributionRegistry implements AutoCloseable {
    private static final int MAXIMUM_ENTRIES = 1024;
    private static final long TTL_NANOS = 5_000_000_000L;
    private final LinkedHashMap<UUID, Attribution> entries = new LinkedHashMap<>();

    synchronized void record(
        UUID target, UUID shooter, UUID matchId, ShotId shotId,
        WeaponId weaponId, long now
    ) {
        cleanup(now);
        entries.put(target, new Attribution(shooter, matchId, shotId, weaponId, now + TTL_NANOS));
        while (entries.size() > MAXIMUM_ENTRIES) {
            entries.remove(entries.keySet().iterator().next());
        }
    }

    synchronized Optional<Attribution> consume(UUID target, long now) {
        cleanup(now);
        Attribution attribution = entries.remove(target);
        return attribution == null || attribution.expiresAtNanos < now
            ? Optional.empty() : Optional.of(attribution);
    }

    synchronized void remove(UUID target) {
        entries.remove(target);
    }

    synchronized void clear() {
        entries.clear();
    }

    private void cleanup(long now) {
        entries.entrySet().removeIf(entry -> entry.getValue().expiresAtNanos < now);
    }

    @Override public void close() {
        clear();
    }

    record Attribution(
        UUID shooterUuid, UUID matchId, ShotId shotId,
        WeaponId weaponId, long expiresAtNanos
    ) {}
}
