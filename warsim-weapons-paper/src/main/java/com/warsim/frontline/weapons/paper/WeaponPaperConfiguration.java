package com.warsim.frontline.weapons.paper;

import com.warsim.frontline.api.weapon.WeaponConfiguration;

record WeaponPaperConfiguration(
    WeaponConfiguration core,
    boolean cancelVanillaDrop,
    boolean cancelVanillaInteraction,
    String error
) {
}
