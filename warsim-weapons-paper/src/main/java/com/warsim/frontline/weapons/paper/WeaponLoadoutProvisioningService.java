package com.warsim.frontline.weapons.paper;

import com.warsim.frontline.api.classes.*;
import com.warsim.frontline.api.weapon.WeaponId;
import com.warsim.frontline.weapons.DefaultWeaponService;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

final class WeaponLoadoutProvisioningService implements CombatLoadoutProvisioningService {
    private static final int MAX_TOKENS = 512;
    private static final int MAX_LOADOUTS = 512;

    private final WarSimWeaponsPlugin plugin;
    private final DefaultWeaponService weaponService;
    private final CraftEngineWeaponGateway gateway;
    private final UUID providerInstanceId = UUID.randomUUID();
    private final NamespacedKey providerKey;
    private final NamespacedKey matchKey;
    private final NamespacedKey deploymentKey;
    private final NamespacedKey lifeKey;
    private final NamespacedKey itemInstanceKey;
    private final NamespacedKey tokenKey;
    private final NamespacedKey weaponKey;
    private final Map<UUID, TokenEntry> tokens = new HashMap<>();
    private final ArrayDeque<UUID> tokenOrder = new ArrayDeque<>();
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
        this.providerKey = new NamespacedKey(plugin, "managed_provider");
        this.matchKey = new NamespacedKey(plugin, "managed_match");
        this.deploymentKey = new NamespacedKey(plugin, "managed_deployment_revision");
        this.lifeKey = new NamespacedKey(plugin, "managed_life_revision");
        this.itemInstanceKey = new NamespacedKey(plugin, "managed_item_instance");
        this.tokenKey = new NamespacedKey(plugin, "managed_loadout_token");
        this.weaponKey = new NamespacedKey(plugin, "managed_weapon_id");
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
        if (entry == null) {
            return LoadoutProvisionResult.rejected("Loadout token已失效或已使用");
        }
        if (!entry.token().equals(token) || !token.providerInstanceId().equals(providerInstanceId)) {
            return LoadoutProvisionResult.rejected("Loadout token身份不匹配");
        }
        if (System.nanoTime() > entry.token().expiresAtMonotonic()) {
            return LoadoutProvisionResult.rejected("Loadout token已过期");
        }
        Player player = Bukkit.getPlayer(entry.token().playerUuid());
        if (player == null) {
            return LoadoutProvisionResult.rejected("玩家不在线，无法发放装备");
        }
        LifeKey key = new LifeKey(
            entry.token().playerUuid(), entry.token().matchId(),
            entry.token().deploymentRevision(), entry.token().proposedLifeRevision()
        );
        clearLife(key);
        List<ItemStack> created = new ArrayList<>();
        Set<UUID> managedItemIds = new HashSet<>();
        Set<WeaponId> weaponIds = new HashSet<>();
        for (Map.Entry<EquipmentSlotType, EquipmentReference> slot : entry.equipment().slots().entrySet()) {
            EquipmentReference reference = slot.getValue();
            if (reference instanceof EmptyEquipmentReference) continue;
            Optional<ItemStack> item = Optional.empty();
            Optional<WeaponId> weaponId = Optional.empty();
            if (reference instanceof WeaponEquipmentReference weapon) {
                weaponId = Optional.of(weapon.weaponId());
                item = gateway.create(weapon.weaponId(), player);
                weaponIds.add(weapon.weaponId());
            } else if (reference instanceof CraftEngineEquipmentReference craft) {
                item = gateway.createCraftEngineItem(craft.namespacedItemId(), player);
            }
            if (item.isEmpty()) {
                removeCreated(player, created);
                return LoadoutProvisionResult.rejected("装备生成失败: " + reference);
            }
            UUID managedItemId = UUID.randomUUID();
            markManaged(item.get(), entry.token(), managedItemId, weaponId);
            managedItemIds.add(managedItemId);
            created.add(item.get());
        }
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(created.toArray(ItemStack[]::new));
        if (!leftovers.isEmpty()) {
            removeCreated(player, created);
            return LoadoutProvisionResult.rejected("背包空间不足，装备未发放");
        }
        for (WeaponId weaponId : weaponIds) {
            weaponService.clearWeapon(entry.token().playerUuid(), entry.token().matchId(), weaponId);
            weaponService.refill(entry.token().playerUuid(), entry.token().matchId(), weaponId);
            weaponService.cancelReload(entry.token().playerUuid(), entry.token().matchId(), weaponId);
        }
        rememberLoadout(key, new ManagedLoadout(key, managedItemIds, weaponIds));
        return LoadoutProvisionResult.success("Loadout granted", entry.token());
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
        Set<UUID> ids = new HashSet<>();
        for (ManagedLoadout loadout : loadouts.values()) {
            if (loadout.key().playerUuid().equals(player)) ids.addAll(loadout.managedItemIds());
        }
        if (ids.isEmpty()) return;
        event.getDrops().removeIf(item -> managedItemId(item).filter(ids::contains).isPresent());
    }

    synchronized void close() {
        closed = true;
        tokens.clear();
        tokenOrder.clear();
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
                if (managedItemId(item).filter(managed.managedItemIds()::contains).isPresent()) {
                    item.setAmount(0);
                }
            }
        }
        managed.weaponIds().forEach(weapon ->
            weaponService.clearWeapon(key.playerUuid(), key.matchId(), weapon));
    }

    private void removeCreated(Player player, List<ItemStack> created) {
        for (ItemStack item : created) {
            UUID id = managedItemId(item).orElse(null);
            if (id == null) continue;
            for (ItemStack inventoryItem : player.getInventory().getContents()) {
                if (managedItemId(inventoryItem).filter(id::equals).isPresent()) {
                    inventoryItem.setAmount(0);
                }
            }
        }
    }

    private void markManaged(
        ItemStack item,
        LoadoutPreparationToken token,
        UUID managedItemId,
        Optional<WeaponId> weaponId
    ) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(providerKey, PersistentDataType.STRING, providerInstanceId.toString());
        pdc.set(matchKey, PersistentDataType.STRING, token.matchId().toString());
        pdc.set(deploymentKey, PersistentDataType.LONG, token.deploymentRevision());
        pdc.set(lifeKey, PersistentDataType.LONG, token.proposedLifeRevision());
        pdc.set(itemInstanceKey, PersistentDataType.STRING, managedItemId.toString());
        pdc.set(tokenKey, PersistentDataType.STRING, token.tokenId().toString());
        weaponId.ifPresent(value -> pdc.set(weaponKey, PersistentDataType.STRING, value.value()));
        item.setItemMeta(meta);
    }

    private Optional<UUID> managedItemId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return Optional.empty();
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        if (!providerInstanceId.toString().equals(pdc.get(providerKey, PersistentDataType.STRING))) {
            return Optional.empty();
        }
        String value = pdc.get(itemInstanceKey, PersistentDataType.STRING);
        if (value == null) return Optional.empty();
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private record TokenEntry(LoadoutPreparationToken token, ClassEquipmentDefinition equipment) {}

    private record LifeKey(UUID playerUuid, UUID matchId, long deploymentRevision, long lifeRevision) {}

    private record ManagedLoadout(
        LifeKey key,
        Set<UUID> managedItemIds,
        Set<WeaponId> weaponIds
    ) {
        ManagedLoadout {
            managedItemIds = Set.copyOf(managedItemIds);
            weaponIds = Set.copyOf(weaponIds);
        }
    }
}
