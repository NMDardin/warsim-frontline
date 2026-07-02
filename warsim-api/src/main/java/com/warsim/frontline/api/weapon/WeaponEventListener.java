package com.warsim.frontline.api.weapon;

@FunctionalInterface
public interface WeaponEventListener {
    void onEvent(WeaponEvent event);
}
