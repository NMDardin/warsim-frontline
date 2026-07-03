package com.warsim.frontline.weapons.paper;

import com.warsim.frontline.api.classes.*;
import com.warsim.frontline.api.weapon.WeaponId;
import com.warsim.frontline.api.weapon.WeaponOperationResult;
import com.warsim.frontline.api.weapon.WeaponRuntimeState;
import com.warsim.frontline.weapons.DefaultWeaponService;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
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

    @Override public synchronized boolean isAvailable() {
        return !closed && weaponService.state() == com.warsim.frontline.api.weapon.WeaponSystemState.ACTIVE;
    }

    @Override public UUID providerInstanceId() { return providerInstanceId; }

    @Override
    public synchronized LoadoutValidationResult validateLoadout(ClassEquipmentDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        for (EquipmentReference reference : definition.slots().values()) {
            if (reference instanceof WeaponEquipmentReference weapon
                && weaponService.definition(weapon.weaponId()).isEmpty()) {
                return LoadoutValidationResult.rejected("Unknown weapon: " + weapon.weaponId().value());
            }
            if (reference instanceof CraftEngineEquipmentReference item
                && !gateway.exists(item.namespacedItemId())) {
                return LoadoutValidationResult.rejected("CraftEngine item does not exist: " + item.namespacedItemId());
            }
        }
        return LoadoutValidationResult.ok();
    }

    @Override
    public synchronized LoadoutProvisionResult prepareLoadout(LoadoutPreparationRequest request) {
        if (!isAvailable()) return LoadoutProvisionResult.rejected("Loadout service is unavailable");
        pruneExpiredTokens(System.nanoTime());
        if (tokens.size() >= MAX_TOKENS) {
            plugin.getLogger().warning("[warsim-weapons] Loadout token table is full; rejecting prepareLoadout.");
            return LoadoutProvisionResult.rejected("Loadout token table is full");
        }
        LoadoutValidationResult validation = validateLoadout(request.equipment());
        if (!validation.valid()) return LoadoutProvisionResult.rejected(validation.message());
        LoadoutPreparationToken token = new LoadoutPreparationToken(
            UUID.randomUUID(), request.playerUuid(), request.matchId(), request.lifecycleRevision(),
            request.deploymentRevision(), request.currentLifeRevision(), request.proposedLifeRevision(),
            request.combatClassId(), request.classConfigurationRevision(), providerInstanceId,
            request.createdAtMonotonic(), request.expiresAtMonotonic()
        );
        rememberToken(token, request.equipment());
        return LoadoutProvisionResult.success("Loadout prepared", token);
    }

    @Override
    public synchronized LoadoutProvisionResult cancelPreparedLoadout(LoadoutPreparationToken token, String reason) {
        Objects.requireNonNull(token, "token");
        if (!token.providerInstanceId().equals(providerInstanceId)) {
            plugin.getLogger().warning("[warsim-weapons] Prepared token cancel rejected: providerInstanceId mismatch.");
            return LoadoutProvisionResult.rejected("providerInstanceId mismatch");
        }
        TokenEntry entry = tokens.get(token.tokenId());
        if (entry == null) {
            return LoadoutProvisionResult.success("No active prepared token", null);
        }
        if (!entry.token().equals(token)) {
            plugin.getLogger().warning("[warsim-weapons] Prepared token cancel rejected: token identity mismatch.");
            return LoadoutProvisionResult.rejected("Loadout token identity mismatch");
        }
        removeToken(token.tokenId());
        if (System.nanoTime() > token.expiresAtMonotonic()) {
            return LoadoutProvisionResult.success("Expired prepared token cleared", null);
        }
        return LoadoutProvisionResult.success("Prepared loadout cancelled", null);
    }

    @Override
    public synchronized LoadoutProvisionResult grantPreparedLoadout(LoadoutPreparationToken token) {
        Objects.requireNonNull(token, "token");
        TokenEntry entry = tokens.get(token.tokenId());
        if (entry == null) return LoadoutProvisionResult.rejected("Loadout token is invalid or already used");
        if (!entry.token().equals(token) || !entry.token().providerInstanceId().equals(providerInstanceId)) {
            return LoadoutProvisionResult.rejected("Loadout token identity mismatch");
        }
        if (System.nanoTime() > entry.token().expiresAtMonotonic()) {
            removeToken(entry.token().tokenId());
            return LoadoutProvisionResult.rejected("Loadout token expired");
        }
        Player player = Bukkit.getPlayer(entry.token().playerUuid());
        if (player == null) return LoadoutProvisionResult.rejected("Player is offline; cannot grant loadout");

        LifeKey key = new LifeKey(
            entry.token().playerUuid(),
            entry.token().matchId(),
            entry.token().deploymentRevision(),
            entry.token().proposedLifeRevision()
        );
        ManagedLoadout previousManaged = loadouts.get(key);
        if (previousManaged == null && loadouts.size() >= MAX_LOADOUTS) {
            plugin.getLogger().warning("[warsim-weapons] Managed loadout table is full; rejecting grantPreparedLoadout.");
            return LoadoutProvisionResult.rejected("Managed loadout table is full");
        }

        GrantTransaction tx = new GrantTransaction(
            player,
            key,
            new HashMap<>(loadouts),
            new ArrayDeque<>(loadoutOrder),
            deepClone(player.getInventory().getStorageContents())
        );
        try {
            PlannedLoadout planned = createManagedItems(player, entry);
            tx.createdManagedItemIds.addAll(planned.managedItemIds());
            tx.newWeaponIds.addAll(planned.weaponIds());
            tx.weaponSnapshots.putAll(snapshotWeaponStates(
                key.playerUuid(), key.matchId(), previousManaged, planned.weaponIds()
            ));
            ItemStack[] storagePlan = deepClone(tx.previousStorageContents);
            if (previousManaged != null) {
                removeManagedItems(storagePlan, previousManaged.managedItemIds());
            }
            if (!addItemsToStorage(player.getInventory(), storagePlan, planned.items())) {
                return LoadoutProvisionResult.rejected("Not enough storage inventory space; loadout was not granted");
            }
            player.getInventory().setStorageContents(deepClone(storagePlan));
            tx.inventoryCommitted = true;

            rememberLoadout(key, new ManagedLoadout(key, planned.managedItemIds(), planned.weaponIds()));
            tx.loadoutCommitted = true;

            for (WeaponId weaponId : planned.weaponIds()) {
                weaponService.clearWeapon(key.playerUuid(), key.matchId(), weaponId);
                WeaponOperationResult refill = weaponService.refill(key.playerUuid(), key.matchId(), weaponId);
                if (!refill.successful()) throw new IllegalStateException(refill.message());
                weaponService.cancelReload(key.playerUuid(), key.matchId(), weaponId);
            }
            if (previousManaged != null) {
                for (WeaponId weaponId : previousManaged.weaponIds()) {
                    if (!planned.weaponIds().contains(weaponId)) {
                        weaponService.clearWeapon(key.playerUuid(), key.matchId(), weaponId);
                    }
                }
            }
            tx.weaponStatesCommitted = true;
            TokenEntry finalEntry = tokens.get(entry.token().tokenId());
            if (finalEntry == null) {
                rollbackGrant(tx);
                return LoadoutProvisionResult.rejected("Loadout token is no longer active");
            }
            if (!finalEntry.token().equals(entry.token())
                || !finalEntry.token().equals(token)
                || !finalEntry.token().providerInstanceId().equals(providerInstanceId)) {
                rollbackGrant(tx);
                return LoadoutProvisionResult.rejected("Loadout token identity mismatch");
            }
            removeToken(entry.token().tokenId());
            tx.tokenConsumed = true;
            return LoadoutProvisionResult.success("Loadout granted", entry.token());
        } catch (RuntimeException exception) {
            rollbackGrant(tx);
            plugin.getLogger().warning("[warsim-weapons] grantPreparedLoadout rolled back: " + exception.getMessage());
            return LoadoutProvisionResult.rejected("Loadout grant failed");
        }
    }

    @Override
    public synchronized LoadoutProvisionResult clearManagedLoadout(ManagedLoadoutClearRequest request) {
        if (!request.providerInstanceId().equals(providerInstanceId)) {
            return LoadoutProvisionResult.rejected("providerInstanceId mismatch");
        }
        clearLife(new LifeKey(
            request.playerUuid(), request.matchId(), request.deploymentRevision(), request.lifeRevision()
        ));
        return LoadoutProvisionResult.success("Managed loadout cleared", null);
    }

    @Override
    public synchronized LoadoutProvisionResult resetCombatLifeState(CombatLifeResetRequest request) {
        if (!request.providerInstanceId().equals(providerInstanceId)) {
            return LoadoutProvisionResult.rejected("providerInstanceId mismatch");
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

    synchronized void clearPlayer(Player player, String reason) {
        Objects.requireNonNull(player, "player");
        clearLoadouts(
            key -> key.playerUuid().equals(player.getUniqueId()),
            player,
            "player " + reason
        );
    }

    synchronized void clearPlayer(UUID playerUuid, String reason) {
        clearLoadouts(
            key -> key.playerUuid().equals(playerUuid),
            Bukkit.getPlayer(playerUuid),
            "player " + reason
        );
    }

    synchronized void clearMatch(UUID matchId, String reason) {
        for (UUID playerUuid : loadouts.keySet().stream()
            .filter(key -> key.matchId().equals(matchId))
            .map(LifeKey::playerUuid)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))) {
            clearLoadouts(
                key -> key.matchId().equals(matchId) && key.playerUuid().equals(playerUuid),
                Bukkit.getPlayer(playerUuid),
                "match " + reason
            );
        }
    }

    synchronized void reconcilePlayerInventory(Player player) {
        Objects.requireNonNull(player, "player");
        Set<UUID> indexed = new HashSet<>();
        for (ManagedLoadout managed : loadouts.values()) {
            if (managed.key().playerUuid().equals(player.getUniqueId())) {
                indexed.addAll(managed.managedItemIds());
            }
        }
        PlayerInventory inventory = player.getInventory();
        ItemStack[] storage = deepClone(inventory.getStorageContents());
        boolean changed = false;
        for (int index = 0; index < storage.length; index++) {
            ItemStack item = storage[index];
            Optional<UUID> current = currentManagedItemId(item);
            if (current.isPresent()) {
                if (!indexed.contains(current.get())) {
                    storage[index] = null;
                    changed = true;
                }
                continue;
            }
            if (hasWarSimManagedMetadata(item)) {
                storage[index] = null;
                changed = true;
            }
        }
        if (changed) inventory.setStorageContents(storage);
    }

    synchronized void removeManagedDeathDrops(PlayerDeathEvent event) {
        UUID player = event.getPlayer().getUniqueId();
        Set<UUID> ids = new HashSet<>();
        for (ManagedLoadout loadout : loadouts.values()) {
            if (loadout.key().playerUuid().equals(player)) ids.addAll(loadout.managedItemIds());
        }
        if (ids.isEmpty()) return;
        event.getDrops().removeIf(item -> currentManagedItemId(item).filter(ids::contains).isPresent());
    }

    synchronized void close() {
        closed = true;
        int offline = 0;
        for (UUID playerUuid : loadouts.keySet().stream()
            .map(LifeKey::playerUuid)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))) {
            Player player = Bukkit.getPlayer(playerUuid);
            if (player == null) offline++;
            clearLoadouts(key -> key.playerUuid().equals(playerUuid), player, "close");
        }
        if (offline > 0) {
            plugin.getLogger().warning("[warsim-weapons] " + offline
                + " offline player inventory cleanup(s) deferred until next WarSim managed item reconcile.");
        }
        tokens.clear();
        tokenOrder.clear();
    }

    private void rollbackGrant(GrantTransaction tx) {
        if (tx.inventoryCommitted) {
            try {
                tx.player.getInventory().setStorageContents(deepClone(tx.previousStorageContents));
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("[warsim-weapons] Failed to restore storage inventory after loadout rollback.");
            }
        }
        loadouts.clear();
        loadouts.putAll(tx.previousLoadouts);
        loadoutOrder.clear();
        loadoutOrder.addAll(tx.previousLoadoutOrder);
        for (Map.Entry<WeaponId, Optional<WeaponRuntimeState>> entry : tx.weaponSnapshots.entrySet()) {
            if (entry.getValue().isPresent()) {
                weaponService.restoreRuntimeState(entry.getValue().get());
            } else {
                weaponService.clearWeapon(tx.key.playerUuid(), tx.key.matchId(), entry.getKey());
            }
        }
    }

    private PlannedLoadout createManagedItems(Player player, TokenEntry entry) {
        List<ItemStack> created = new ArrayList<>();
        Set<UUID> managedItemIds = new LinkedHashSet<>();
        Set<WeaponId> weaponIds = new LinkedHashSet<>();
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
                throw new IllegalStateException("Loadout item creation failed: " + reference);
            }
            UUID managedItemId = UUID.randomUUID();
            markManaged(item.get(), entry.token(), managedItemId, weaponId);
            managedItemIds.add(managedItemId);
            created.add(item.get().clone());
        }
        return new PlannedLoadout(created, managedItemIds, weaponIds);
    }

    private Map<WeaponId, Optional<WeaponRuntimeState>> snapshotWeaponStates(
        UUID playerUuid,
        UUID matchId,
        ManagedLoadout previousManaged,
        Set<WeaponId> newWeaponIds
    ) {
        Set<WeaponId> affected = new LinkedHashSet<>(newWeaponIds);
        if (previousManaged != null) affected.addAll(previousManaged.weaponIds());
        Map<WeaponId, Optional<WeaponRuntimeState>> snapshots = new HashMap<>();
        for (WeaponId weaponId : affected) {
            snapshots.put(weaponId, weaponService.runtimeState(playerUuid, matchId, weaponId));
        }
        return snapshots;
    }

    private boolean addItemsToStorage(PlayerInventory inventory, ItemStack[] storage, List<ItemStack> items) {
        for (ItemStack source : items) {
            ItemStack remaining = source.clone();
            int maxStack = Math.max(1, Math.min(remaining.getMaxStackSize(), inventory.getMaxStackSize()));
            for (int index = 0; index < storage.length && remaining.getAmount() > 0; index++) {
                ItemStack existing = storage[index];
                if (existing == null || existing.getType().isAir() || !existing.isSimilar(remaining)) continue;
                int room = maxStack - existing.getAmount();
                if (room <= 0) continue;
                int moved = Math.min(room, remaining.getAmount());
                existing.setAmount(existing.getAmount() + moved);
                remaining.setAmount(remaining.getAmount() - moved);
            }
            for (int index = 0; index < storage.length && remaining.getAmount() > 0; index++) {
                ItemStack existing = storage[index];
                if (existing != null && !existing.getType().isAir()) continue;
                ItemStack placed = remaining.clone();
                int moved = Math.min(maxStack, remaining.getAmount());
                placed.setAmount(moved);
                storage[index] = placed;
                remaining.setAmount(remaining.getAmount() - moved);
            }
            if (remaining.getAmount() > 0) return false;
        }
        return true;
    }

    private void rememberToken(LoadoutPreparationToken token, ClassEquipmentDefinition equipment) {
        tokens.put(token.tokenId(), new TokenEntry(token, equipment));
        tokenOrder.addLast(token.tokenId());
    }

    private void removeToken(UUID tokenId) {
        tokens.remove(tokenId);
        tokenOrder.remove(tokenId);
    }

    private void pruneExpiredTokens(long nowNanos) {
        for (UUID tokenId : List.copyOf(tokenOrder)) {
            TokenEntry entry = tokens.get(tokenId);
            if (entry == null || entry.token().expiresAtMonotonic() < nowNanos) removeToken(tokenId);
        }
    }

    private void rememberLoadout(LifeKey key, ManagedLoadout loadout) {
        if (!loadouts.containsKey(key)) loadoutOrder.addLast(key);
        loadouts.put(key, loadout);
    }

    private void clearLife(LifeKey key) {
        ManagedLoadout managed = loadouts.get(key);
        if (managed == null) return;
        Player player = Bukkit.getPlayer(key.playerUuid());
        if (player != null) {
            try {
                clearManagedItems(player, managed.managedItemIds());
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("[warsim-weapons] Managed loadout inventory cleanup failed; index was still cleared.");
            }
        } else {
            plugin.getLogger().warning("[warsim-weapons] Managed loadout inventory cleanup for offline player deferred until next reconcile.");
        }
        loadouts.remove(key);
        loadoutOrder.remove(key);
        managed.weaponIds().forEach(weapon -> weaponService.clearWeapon(key.playerUuid(), key.matchId(), weapon));
    }

    private void clearLoadouts(Predicate<LifeKey> predicate, Player player, String reason) {
        List<ManagedLoadout> selected = loadouts.entrySet().stream()
            .filter(entry -> predicate.test(entry.getKey()))
            .map(Map.Entry::getValue)
            .toList();
        if (selected.isEmpty()) return;
        Set<UUID> managedIds = new HashSet<>();
        for (ManagedLoadout managed : selected) {
            managedIds.addAll(managed.managedItemIds());
            managed.weaponIds().forEach(weapon ->
                weaponService.clearWeapon(managed.key().playerUuid(), managed.key().matchId(), weapon));
            loadouts.remove(managed.key());
            loadoutOrder.remove(managed.key());
        }
        if (player != null && !managedIds.isEmpty()) {
            try {
                clearManagedItems(player, managedIds);
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("[warsim-weapons] Managed loadout inventory cleanup failed during " + reason + "; index was still cleared.");
            }
        } else if (player == null) {
            plugin.getLogger().warning("[warsim-weapons] Managed loadout inventory cleanup deferred for offline player during " + reason + ".");
        }
    }

    private void clearManagedItems(Player player, Set<UUID> managedIds) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] storage = deepClone(inventory.getStorageContents());
        removeManagedItems(storage, managedIds);
        inventory.setStorageContents(storage);
    }

    private void removeManagedItems(ItemStack[] storage, Set<UUID> managedIds) {
        for (int index = 0; index < storage.length; index++) {
            if (currentManagedItemId(storage[index]).filter(managedIds::contains).isPresent()) {
                storage[index] = null;
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
        if (meta == null) throw new IllegalStateException("Loadout item has no ItemMeta");
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

    private Optional<UUID> currentManagedItemId(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return Optional.empty();
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

    private boolean hasWarSimManagedMetadata(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        String provider = pdc.get(providerKey, PersistentDataType.STRING);
        String itemId = pdc.get(itemInstanceKey, PersistentDataType.STRING);
        String match = pdc.get(matchKey, PersistentDataType.STRING);
        Long deployment = pdc.get(deploymentKey, PersistentDataType.LONG);
        Long life = pdc.get(lifeKey, PersistentDataType.LONG);
        String token = pdc.get(tokenKey, PersistentDataType.STRING);
        String weapon = pdc.get(weaponKey, PersistentDataType.STRING);
        if (provider == null && itemId == null && match == null && deployment == null
            && life == null && token == null && weapon == null) {
            return false;
        }
        return !validUuid(provider)
            || !validUuid(itemId)
            || !validUuid(match)
            || deployment == null
            || life == null
            || !providerInstanceId.toString().equals(provider);
    }

    private static boolean validUuid(String value) {
        if (value == null) return false;
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static ItemStack[] deepClone(ItemStack[] contents) {
        ItemStack[] copy = new ItemStack[contents.length];
        for (int index = 0; index < contents.length; index++) {
            copy[index] = contents[index] == null ? null : contents[index].clone();
        }
        return copy;
    }

    private record TokenEntry(LoadoutPreparationToken token, ClassEquipmentDefinition equipment) {}
    private record LifeKey(UUID playerUuid, UUID matchId, long deploymentRevision, long lifeRevision) {}
    private record ManagedLoadout(LifeKey key, Set<UUID> managedItemIds, Set<WeaponId> weaponIds) {
        ManagedLoadout {
            managedItemIds = Set.copyOf(managedItemIds);
            weaponIds = Set.copyOf(weaponIds);
        }
    }
    private record PlannedLoadout(
        List<ItemStack> items,
        Set<UUID> managedItemIds,
        Set<WeaponId> weaponIds
    ) {
        PlannedLoadout {
            items = List.copyOf(items);
            managedItemIds = Set.copyOf(managedItemIds);
            weaponIds = Set.copyOf(weaponIds);
        }
    }
    private static final class GrantTransaction {
        private final Player player;
        private final LifeKey key;
        private final Map<LifeKey, ManagedLoadout> previousLoadouts;
        private final ArrayDeque<LifeKey> previousLoadoutOrder;
        private final ItemStack[] previousStorageContents;
        private final Map<WeaponId, Optional<WeaponRuntimeState>> weaponSnapshots = new HashMap<>();
        private final Set<UUID> createdManagedItemIds = new HashSet<>();
        private final Set<WeaponId> newWeaponIds = new HashSet<>();
        private boolean inventoryCommitted;
        private boolean loadoutCommitted;
        private boolean weaponStatesCommitted;
        private boolean tokenConsumed;

        private GrantTransaction(
            Player player,
            LifeKey key,
            Map<LifeKey, ManagedLoadout> previousLoadouts,
            ArrayDeque<LifeKey> previousLoadoutOrder,
            ItemStack[] previousStorageContents
        ) {
            this.player = player;
            this.key = key;
            this.previousLoadouts = previousLoadouts;
            this.previousLoadoutOrder = previousLoadoutOrder;
            this.previousStorageContents = previousStorageContents;
        }
    }
}
