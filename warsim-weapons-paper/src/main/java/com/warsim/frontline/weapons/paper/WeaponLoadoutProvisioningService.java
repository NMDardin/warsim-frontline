package com.warsim.frontline.weapons.paper;

import com.warsim.frontline.api.classes.*;
import com.warsim.frontline.api.weapon.WeaponId;
import com.warsim.frontline.weapons.DefaultWeaponService;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;

final class WeaponLoadoutProvisioningService implements CombatLoadoutProvisioningService {
    private static final int MAX_TOKENS = 512;
    private static final int MAX_LOADOUTS = 512;

    private final WarSimWeaponsPlugin plugin;
    private final DefaultWeaponService weaponService;
    private final CraftEngineWeaponGateway gateway;
    private final UUID providerInstanceId = UUID.randomUUID();
    private final Map<UUID, TokenEntry> tokens = new HashMap<>();
    private final ArrayDeque<UUID> tokenOrder = new ArrayDeque<>();
    private final Set<UUID> consumedTokens = new HashSet<>();
    private final Map<LifeKey, ManagedLoadout> loadouts = new HashMap<>();
    private final ArrayDeque<LifeKey> loadoutOrder = new ArrayDeque<>();
    private boolean closed;

    WeaponLoadoutProvisioningService(
        WarSimWeaponsPlugin plugin,
        DefaultWeaponService weaponService,
        CraftEngineWeaponGateway gateway
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.weaponService = Objects.requireNonNull(weaponService, "weaponService");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    @Override
    public synchronized boolean isAvailable() {
        return !closed && weaponService.state() == com.warsim.frontline.api.weapon.WeaponSystemState.ACTIVE;
    }

    @Override
    public UUID providerInstanceId() {
        return providerInstanceId;
    }

    @Override
    public synchronized LoadoutValidationResult validateLoadout(ClassEquipmentDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        for (EquipmentReference reference : definition.slots().values()) {
            if (reference instanceof WeaponEquipmentReference weapon
                && weaponService.definition(weapon.weaponId()).isEmpty()) {
                return LoadoutValidationResult.rejected("未知武器: " + weapon.weaponId().value());
            }
            if (reference instanceof CraftEngineEquipmentReference item
                && !gateway.exists(item.namespacedItemId())) {
                return LoadoutValidationResult.rejected("CraftEngine物品不存在: " + item.namespacedItemId());
            }
        }
        return LoadoutValidationResult.ok();
    }

    @Override
    public synchronized LoadoutProvisionResult prepareLoadout(LoadoutPreparationRequest request) {
        if (!isAvailable()) return LoadoutProvisionResult.rejected("装备服务不可用");
        LoadoutValidationResult validation = validateLoadout(request.equipment());
        if (!validation.valid()) return LoadoutProvisionResult.rejected(validation.message());
        LoadoutPreparationToken token = new LoadoutPreparationToken(
            UUID.randomUUID(),
            request.playerUuid(),
            request.matchId(),
            request.lifecycleRevision(),
            request.deploymentRevision(),
            request.currentLifeRevision(),
            request.proposedLifeRevision(),
            request.combatClassId(),
            request.classConfigurationRevision(),
            providerInstanceId,
            request.createdAtMonotonic(),
            request.expiresAtMonotonic()
        );
        rememberToken(token, request.equipment());
        return LoadoutProvisionResult.success("Loadout prepared", token);
    }

    @Override
    public synchronized LoadoutProvisionResult grantPreparedLoadout(LoadoutPreparationToken token) {
        TokenEntry entry = tokens.remove(token.tokenId());
        tokenOrder.remove(token.tokenId());
        if (entry == null || consumedTokens.contains(token.tokenId())) {
            return LoadoutProvisionResult.rejected("Loadout token已失效或已使用");
        }
        if (!token.providerInstanceId().equals(providerInstanceId)) {
            return LoadoutProvisionResult.rejected("Loadout provider不匹配");
        }
        if (System.nanoTime() > token.expiresAtMonotonic()) {
            return LoadoutProvisionResult.rejected("Loadout token已过期");
        }
        consumedTokens.add(token.tokenId());
        Player player = Bukkit.getPlayer(token.playerUuid());
        if (player == null) {
            return LoadoutProvisionResult.rejected("玩家不在线，无法发放装备");
        }
        LifeKey key = new LifeKey(
            token.playerUuid(), token.matchId(), token.deploymentRevision(),
            token.proposedLifeRevision()
        );
        clearLife(key);
        List<ItemStack> created = new ArrayList<>();
        Set<String> managedItemIds = new HashSet<>();
        Set<WeaponId> weaponIds = new HashSet<>();
        for (Map.Entry<EquipmentSlotType, EquipmentReference> slot : entry.equipment().slots().entrySet()) {
            EquipmentReference reference = slot.getValue();
            if (reference instanceof EmptyEquipmentReference) continue;
            Optional<ItemStack> item = Optional.empty();
            if (reference instanceof WeaponEquipmentReference weapon) {
                item = gateway.create(weapon.weaponId(), player);
                weaponIds.add(weapon.weaponId());
                weaponService.refill(token.playerUuid(), token.matchId(), weapon.weaponId());
                weaponService.clearWeapon(token.playerUuid(), token.matchId(), weapon.weaponId());
                weaponService.refill(token.playerUuid(), token.matchId(), weapon.weaponId());
                weaponService.cancelReload(token.playerUuid(), token.matchId(), weapon.weaponId());
            } else if (reference instanceof CraftEngineEquipmentReference craft) {
                item = gateway.createCraftEngineItem(craft.namespacedItemId(), player);
            }
            if (item.isEmpty()) {
                removeCreated(player, created);
                return LoadoutProvisionResult.rejected("装备生成失败: " + reference);
            }
            gateway.customItemId(item.get()).ifPresent(managedItemIds::add);
            created.add(item.get());
        }
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(created.toArray(ItemStack[]::new));
        if (!leftovers.isEmpty()) {
            removeCreated(player, created);
            return LoadoutProvisionResult.rejected("背包空间不足，装备未发放");
        }
        rememberLoadout(key, new ManagedLoadout(key, managedItemIds, weaponIds));
        return LoadoutProvisionResult.success("Loadout granted", token);
    }

    @Override
    public synchronized LoadoutProvisionResult clearManagedLoadout(ManagedLoadoutClearRequest request) {
        if (!request.providerInstanceId().equals(providerInstanceId)) {
            return LoadoutProvisionResult.rejected("providerInstanceId不匹配");
        }
        clearLife(new LifeKey(
            request.playerUuid(), request.matchId(), request.deploymentRevision(), request.lifeRevision()
        ));
        return LoadoutProvisionResult.success("Managed loadout cleared", null);
    }

    @Override
    public synchronized LoadoutProvisionResult resetCombatLifeState(CombatLifeResetRequest request) {
        if (!request.providerInstanceId().equals(providerInstanceId)) {
            return LoadoutProvisionResult.rejected("providerInstanceId不匹配");
        }
        LifeKey key = new LifeKey(
            request.playerUuid(), request.matchId(), request.deploymentRevision(), request.lifeRevision()
        );
        ManagedLoadout managed = loadouts.get(key);
        if (managed != null) {
            managed.weaponIds().forEach(weapon ->
                weaponService.clearWeapon(request.playerUuid(), request.matchId(), weapon));
        }
        return LoadoutProvisionResult.success("Combat life state reset", null);
    }

    synchronized void removeManagedDeathDrops(PlayerDeathEvent event) {
        UUID player = event.getPlayer().getUniqueId();
        Set<String> ids = new HashSet<>();
        for (ManagedLoadout loadout : loadouts.values()) {
            if (loadout.key().playerUuid().equals(player)) ids.addAll(loadout.managedItemIds());
        }
        if (ids.isEmpty()) return;
        event.getDrops().removeIf(item ->
            gateway.customItemId(item).filter(ids::contains).isPresent());
    }

    synchronized void close() {
        closed = true;
        tokens.clear();
        tokenOrder.clear();
        consumedTokens.clear();
        loadouts.clear();
        loadoutOrder.clear();
    }

    private void rememberToken(LoadoutPreparationToken token, ClassEquipmentDefinition equipment) {
        tokens.put(token.tokenId(), new TokenEntry(token, equipment));
        tokenOrder.addLast(token.tokenId());
        while (tokenOrder.size() > MAX_TOKENS) {
            UUID expired = tokenOrder.removeFirst();
            tokens.remove(expired);
        }
    }

    private void rememberLoadout(LifeKey key, ManagedLoadout loadout) {
        loadouts.put(key, loadout);
        loadoutOrder.addLast(key);
        while (loadoutOrder.size() > MAX_LOADOUTS) {
            LifeKey removed = loadoutOrder.removeFirst();
            loadouts.remove(removed);
        }
    }

    private void clearLife(LifeKey key) {
        ManagedLoadout managed = loadouts.remove(key);
        loadoutOrder.remove(key);
        if (managed == null) return;
        Player player = Bukkit.getPlayer(key.playerUuid());
        if (player != null) {
            for (ItemStack item : player.getInventory().getContents()) {
                if (gateway.customItemId(item)
                    .filter(managed.managedItemIds()::contains).isPresent()) {
                    item.setAmount(0);
                }
            }
        }
        managed.weaponIds().forEach(weapon ->
            weaponService.clearWeapon(key.playerUuid(), key.matchId(), weapon));
    }

    private void removeCreated(Player player, List<ItemStack> created) {
        for (ItemStack item : created) {
            String id = gateway.customItemId(item).orElse(null);
            if (id == null) continue;
            for (ItemStack inventoryItem : player.getInventory().getContents()) {
                if (gateway.customItemId(inventoryItem).filter(id::equals).isPresent()) {
                    inventoryItem.setAmount(0);
                }
            }
        }
    }

    private record TokenEntry(LoadoutPreparationToken token, ClassEquipmentDefinition equipment) {}

    private record LifeKey(UUID playerUuid, UUID matchId, long deploymentRevision, long lifeRevision) {}

    private record ManagedLoadout(
        LifeKey key,
        Set<String> managedItemIds,
        Set<WeaponId> weaponIds
    ) {
        ManagedLoadout {
            managedItemIds = Set.copyOf(managedItemIds);
            weaponIds = Set.copyOf(weaponIds);
        }
    }
}
