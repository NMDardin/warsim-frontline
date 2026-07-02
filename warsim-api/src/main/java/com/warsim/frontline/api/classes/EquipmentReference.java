package com.warsim.frontline.api.classes;

/**
 * Platform-neutral reference to a deployable equipment item.
 */
public sealed interface EquipmentReference
    permits WeaponEquipmentReference, CraftEngineEquipmentReference, EmptyEquipmentReference {
    boolean empty();
}
