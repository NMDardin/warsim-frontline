package com.warsim.frontline.api.combat;

public enum SpawnProtectionRemovalReason {
    EXPIRED,
    ATTACK,
    MELEE_ATTACK,
    MOVEMENT,
    OBJECTIVE_PRESENCE,
    DEATH,
    QUIT,
    RESET,
    CLASS_CLEAR,
    NEW_DEPLOYMENT,
    INVALID_POSITION,
    ADMIN_CLEAR,
    PLUGIN_CLOSE
}
