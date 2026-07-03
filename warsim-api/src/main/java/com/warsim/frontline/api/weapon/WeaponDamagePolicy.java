package com.warsim.frontline.api.weapon;

public record WeaponDamagePolicy(
    boolean friendlyFireEnabled,
    boolean selfDamageEnabled
) {
    public static WeaponDamagePolicy failClosed() {
        return new WeaponDamagePolicy(false, false);
    }
}
