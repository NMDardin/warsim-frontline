package com.warsim.frontline.weapons.paper;

import com.warsim.frontline.api.weapon.WeaponDefinition;
import com.warsim.frontline.api.weapon.WeaponId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

final class CraftEngineWeaponGateway {
    private final Map<String, WeaponId> byItemId = new HashMap<>();
    private final Map<WeaponId, WeaponDefinition> definitions = new HashMap<>();

    CraftEngineWeaponGateway(List<WeaponDefinition> loaded) {
        for (WeaponDefinition definition : loaded) {
            byItemId.put(definition.craftEngineItemId(), definition.weaponId());
            definitions.put(definition.weaponId(), definition);
        }
    }

    Optional<WeaponId> identify(ItemStack item) {
        return customItemId(item).flatMap(id -> Optional.ofNullable(byItemId.get(id)));
    }

    Optional<String> customItemId(ItemStack item) {
        if (item == null || item.getType().isAir()) return Optional.empty();
        var key = CraftEngineItems.getCustomItemId(item);
        return key == null ? Optional.empty() : Optional.of(key.asString());
    }

    Optional<ItemStack> create(WeaponId weaponId, Player player) {
        WeaponDefinition definition = definitions.get(weaponId);
        if (definition == null) return Optional.empty();
        var custom = CraftEngineItems.byId(definition.craftEngineItemId());
        return custom == null ? Optional.empty()
            : Optional.of(custom.buildBukkitItem(player));
    }

    Optional<ItemStack> createCraftEngineItem(String itemId, Player player) {
        var custom = CraftEngineItems.byId(itemId);
        return custom == null ? Optional.empty() : Optional.of(custom.buildBukkitItem(player));
    }

    boolean exists(String itemId) {
        return CraftEngineItems.byId(itemId) != null;
    }

    List<String> unavailableBindings() {
        return definitions.values().stream()
            .filter(definition -> CraftEngineItems.byId(
                definition.craftEngineItemId()
            ) == null)
            .map(WeaponDefinition::craftEngineItemId)
            .sorted()
            .toList();
    }
}
