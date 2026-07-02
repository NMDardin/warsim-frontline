package com.warsim.frontline.api.classes;

import com.warsim.frontline.api.weapon.WeaponId;
import java.util.Objects;

public record WeaponEquipmentReference(WeaponId weaponId) implements EquipmentReference {
    public WeaponEquipmentReference {
        Objects.requireNonNull(weaponId, "weaponId");
    }

    @Override
    public boolean empty() {
        return false;
    }
}
