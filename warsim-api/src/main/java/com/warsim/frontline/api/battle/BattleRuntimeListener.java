package com.warsim.frontline.api.battle;

@FunctionalInterface
public interface BattleRuntimeListener {
    void onEvent(BattleRuntimeEvent event);
}
