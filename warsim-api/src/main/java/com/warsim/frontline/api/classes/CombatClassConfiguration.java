package com.warsim.frontline.api.classes;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record CombatClassConfiguration(
    boolean enabled,
    long configurationRevision,
    List<CombatClassDefinition> definitions
) {
    public CombatClassConfiguration {
        Objects.requireNonNull(definitions, "definitions");
        if (configurationRevision < 1) {
            throw new IllegalArgumentException("configurationRevision must be positive");
        }
        LinkedHashMap<CombatClassId, CombatClassDefinition> unique = new LinkedHashMap<>();
        for (CombatClassDefinition definition : definitions) {
            if (unique.putIfAbsent(definition.classId(), definition) != null) {
                throw new IllegalArgumentException("Duplicate combat class id: " + definition.classId());
            }
        }
        definitions = List.copyOf(unique.values());
    }

    public static CombatClassConfiguration defaults(boolean enabled) {
        return new CombatClassConfiguration(enabled, 1, List.of(
            new CombatClassDefinition(CombatClassId.ASSAULT, "突击兵", 100,
                defaultWeapons("wrench_m1895_rifle", "wrench_m1911_pistol")),
            new CombatClassDefinition(CombatClassId.MEDIC, "医护兵", 100,
                defaultWeapons("wrench_m1895_rifle", "wrench_m1911_pistol")),
            new CombatClassDefinition(CombatClassId.SUPPORT, "支援兵", 100,
                defaultWeapons("wrench_m1918_smg", "wrench_m1911_pistol")),
            new CombatClassDefinition(CombatClassId.SCOUT, "侦察兵", 100,
                defaultWeapons("wrench_m1903_marksman", "wrench_m1911_pistol"))
        ));
    }

    public static CombatClassConfiguration disabled() {
        return new CombatClassConfiguration(false, 1, List.of());
    }

    public Map<CombatClassId, CombatClassDefinition> byId() {
        LinkedHashMap<CombatClassId, CombatClassDefinition> values = new LinkedHashMap<>();
        for (CombatClassDefinition definition : definitions) {
            values.put(definition.classId(), definition);
        }
        return Map.copyOf(values);
    }

    public Collection<CombatClassId> ids() {
        return byId().keySet();
    }

    private static ClassEquipmentDefinition defaultWeapons(String primary, String secondary) {
        return new ClassEquipmentDefinition(Map.of(
            EquipmentSlotType.PRIMARY_WEAPON,
            new WeaponEquipmentReference(new com.warsim.frontline.api.weapon.WeaponId(primary)),
            EquipmentSlotType.SECONDARY_WEAPON,
            new WeaponEquipmentReference(new com.warsim.frontline.api.weapon.WeaponId(secondary))
        ));
    }
}
