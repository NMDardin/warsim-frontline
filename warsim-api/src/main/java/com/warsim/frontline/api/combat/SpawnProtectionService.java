package com.warsim.frontline.api.combat;

import java.util.Optional;
import java.util.UUID;

public interface SpawnProtectionService {
    void create(SpawnProtectionSnapshot protection);

    Optional<SpawnProtectionSnapshot> snapshot(UUID playerUuid);

    boolean remove(UUID playerUuid, UUID matchId, long lifeRevision, SpawnProtectionRemovalReason reason);

    boolean removeOnAttack(UUID playerUuid, UUID matchId, long lifeRevision);

    boolean shouldBlockIncomingCombatDamage(UUID targetUuid, UUID matchId, long lifeRevision);
}
