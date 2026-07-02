package com.warsim.frontline.api.battle;

import com.warsim.frontline.api.roster.CombatRelation;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only service exported by the main Paper plugin to battle extensions.
 */
public interface WarSimBattleRuntime {
    BattleRuntimeSnapshot snapshot();

    Optional<BattlePlayerSnapshot> player(UUID playerUuid);

    CombatRelation relation(UUID first, UUID second);

    AutoCloseable subscribe(BattleRuntimeListener listener);
}
