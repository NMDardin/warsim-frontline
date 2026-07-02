package com.warsim.frontline.api.classes;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public record ClassEquipmentDefinition(Map<EquipmentSlotType, EquipmentReference> slots) {
    public ClassEquipmentDefinition {
        Objects.requireNonNull(slots, "slots");
        EnumMap<EquipmentSlotType, EquipmentReference> copy =
            new EnumMap<>(EquipmentSlotType.class);
        for (EquipmentSlotType slot : EquipmentSlotType.values()) {
            copy.put(slot, slots.getOrDefault(slot, EmptyEquipmentReference.INSTANCE));
        }
        slots = Map.copyOf(copy);
    }

    public EquipmentReference slot(EquipmentSlotType slot) {
        return slots.getOrDefault(slot, EmptyEquipmentReference.INSTANCE);
    }
}
