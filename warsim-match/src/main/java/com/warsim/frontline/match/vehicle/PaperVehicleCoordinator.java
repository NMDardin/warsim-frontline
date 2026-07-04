package com.warsim.frontline.match.vehicle;

import com.warsim.frontline.admin.WarSimCommandRegistry;
import com.warsim.frontline.api.battle.*;
import com.warsim.frontline.api.match.MatchState;
import com.warsim.frontline.vehicles.*;
import java.time.Instant;
import java.util.*;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

public final class PaperVehicleCoordinator implements Listener, BattleRuntimeListener, AutoCloseable {
    private final JavaPlugin plugin;
    private final WarSimBattleRuntime battleRuntime;
    private final VehicleConfiguration configuration;
    private final String configurationError;
    private final NamespacedKey runtimeKey;
    private final NamespacedKey vehicleKey;
    private final Map<VehicleId, VehicleDefinition> definitions = new LinkedHashMap<>();
    private final Map<VehicleRuntimeId, ManagedVehicle> vehicles = new LinkedHashMap<>();
    private final Map<UUID, VehicleRuntimeId> anchors = new HashMap<>();
    private final VehicleModelAdapter adapter;
    private VehicleSystemStatus status;
    private AutoCloseable commandRegistration;
    private AutoCloseable battleSubscription;
    private int tickTaskId = -1;
    private boolean closed;
    private long spawnAttempts;
    private long spawnSuccesses;
    private long despawnCount;
    private String lastError;

    public PaperVehicleCoordinator(
        JavaPlugin plugin,
        WarSimBattleRuntime battleRuntime,
        VehicleConfiguration configuration,
        String configurationError
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.battleRuntime = Objects.requireNonNull(battleRuntime, "battleRuntime");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.configurationError = configurationError;
        this.runtimeKey = new NamespacedKey(plugin, "vehicle_runtime_id");
        this.vehicleKey = new NamespacedKey(plugin, "vehicle_id");
        configuration.definitions().forEach(definition -> definitions.put(definition.id(), definition));
        boolean modelEnginePresent = plugin.getServer().getPluginManager().getPlugin("ModelEngine") != null;
        if (!configuration.enabled()) {
            status = VehicleSystemStatus.DISABLED;
            adapter = new FallbackAnchorAdapter();
        } else if (configurationError != null) {
            status = VehicleSystemStatus.FAILED;
            lastError = configurationError;
            adapter = new FallbackAnchorAdapter();
        } else if (configuration.modelEngineRequired()
            && configuration.modelEngineFailClosedWhenMissing()
            && !modelEnginePresent) {
            status = VehicleSystemStatus.FAILED;
            lastError = "ModelEngine is required but not available";
            adapter = new FallbackAnchorAdapter();
        } else {
            status = modelEnginePresent ? VehicleSystemStatus.ACTIVE : VehicleSystemStatus.ACTIVE_FALLBACK;
            adapter = new FallbackAnchorAdapter();
        }
    }

    public void start(WarSimCommandRegistry registry) {
        if (closed) return;
        commandRegistration = registry.register(new VehicleCommandExtension(this));
        if (!configuration.enabled() || status == VehicleSystemStatus.FAILED) {
            return;
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        battleSubscription = battleRuntime.subscribe(this);
        tickTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(
            plugin, this::tick, configuration.tickIntervalTicks(), configuration.tickIntervalTicks()
        );
    }

    VehicleConfiguration configuration() {
        return configuration;
    }

    VehicleSystemStatus status() {
        return status;
    }

    String configurationError() {
        return configurationError;
    }

    Collection<VehicleDefinition> definitions() {
        return List.copyOf(definitions.values());
    }

    List<VehicleRuntimeSnapshot> snapshots() {
        return vehicles.values().stream().map(ManagedVehicle::snapshot).toList();
    }

    public List<String> statusLines() {
        return List.of(
            "§fVehicle状态: §a" + status,
            "§fVehicle启用: §a" + configuration.enabled(),
            "§fVehicle活动数量: §a" + vehicles.size() + "/" + configuration.maximumActiveVehicles(),
            "§fVehicle定义数量: §a" + definitions.size(),
            "§fVehicle模型绑定: §a" + adapter.name(),
            "§fVehicle生成/成功/清理: §a" + spawnAttempts + "/" + spawnSuccesses + "/" + despawnCount,
            "§fVehicle最近错误: §e" + (lastError == null ? "无" : lastError)
        );
    }

    boolean spawn(CommandSender sender, VehicleId vehicleId, Location location) {
        spawnAttempts++;
        if (status == VehicleSystemStatus.DISABLED) {
            sender.sendMessage("§cVehicle subsystem is disabled.");
            return false;
        }
        if (status == VehicleSystemStatus.FAILED) {
            sender.sendMessage("§cVehicle subsystem failed: " + nullToNone(lastError));
            return false;
        }
        VehicleDefinition definition = definitions.get(vehicleId);
        if (definition == null) {
            sender.sendMessage("§cUnknown vehicle id: " + vehicleId);
            return false;
        }
        if (vehicles.size() >= configuration.maximumActiveVehicles()) {
            sender.sendMessage("§cVehicle active limit reached.");
            return false;
        }
        if (!configuration.allowAdminSpawnOutsidePlaying() && !playing()) {
            sender.sendMessage("§cVehicles can only be spawned during PLAYING.");
            return false;
        }
        if (!canUseLocation(location)) {
            sender.sendMessage("§cTarget world or chunk is not loaded.");
            return false;
        }
        try {
            VehicleRuntimeId runtimeId = VehicleRuntimeId.random();
            VehicleAnchor anchor = adapter.spawn(definition, runtimeId, location);
            ManagedVehicle vehicle = new ManagedVehicle(
                runtimeId, definition, anchor, null, 0.0, Instant.now(), Instant.now()
            );
            vehicles.put(runtimeId, vehicle);
            anchors.put(anchor.entity().getUniqueId(), runtimeId);
            spawnSuccesses++;
            sender.sendMessage("§aSpawned vehicle " + definition.displayName()
                + " runtime=" + runtimeId.shortText());
            return true;
        } catch (RuntimeException exception) {
            lastError = "Vehicle spawn failed: " + exception.getMessage();
            plugin.getLogger().log(Level.WARNING, "[warsim-vehicle] Vehicle spawn failed.", exception);
            sender.sendMessage("§cVehicle spawn failed.");
            return false;
        }
    }

    boolean despawn(CommandSender sender, String idOrAll) {
        if ("all".equalsIgnoreCase(idOrAll)) {
            int count = vehicles.size();
            despawnAll("command");
            sender.sendMessage("§aDespawned vehicles: " + count);
            return true;
        }
        VehicleRuntimeId runtimeId = findRuntime(idOrAll);
        if (runtimeId == null) {
            sender.sendMessage("§cUnknown or ambiguous vehicle runtime id.");
            return false;
        }
        despawn(runtimeId, "command");
        sender.sendMessage("§aDespawned vehicle " + runtimeId.shortText());
        return true;
    }

    boolean move(CommandSender sender, String idText, String mode, double amount) {
        VehicleRuntimeId runtimeId = findRuntime(idText);
        if (runtimeId == null) {
            sender.sendMessage("§cUnknown or ambiguous vehicle runtime id.");
            return false;
        }
        ManagedVehicle vehicle = vehicles.get(runtimeId);
        Entity entity = vehicle.anchor.entity();
        Location current = entity.getLocation();
        if ("turn".equalsIgnoreCase(mode)) {
            double clamped = Math.max(-360.0, Math.min(360.0, amount));
            current.setYaw((float) (current.getYaw() + clamped));
        } else if ("forward".equalsIgnoreCase(mode)) {
            double clamped = Math.max(-20.0, Math.min(20.0, amount));
            Vector direction = current.getDirection().setY(0);
            if (direction.lengthSquared() > 0) direction.normalize().multiply(clamped);
            current.add(direction);
        } else {
            sender.sendMessage("§eUsage: /warsim vehicle move <runtime> <forward|turn> <amount>");
            return false;
        }
        if (!canUseLocation(current)) {
            sender.sendMessage("§cDestination world or chunk is not loaded.");
            return false;
        }
        if (!entity.teleport(current)) {
            sender.sendMessage("§cVehicle teleport was rejected.");
            return false;
        }
        vehicle.lastUpdatedAt = Instant.now();
        sender.sendMessage("§aVehicle moved.");
        return true;
    }

    VehicleRuntimeSnapshot inspect(String idText) {
        VehicleRuntimeId runtimeId = findRuntime(idText);
        return runtimeId == null ? null : vehicles.get(runtimeId).snapshot();
    }

    @Override
    public void onEvent(BattleRuntimeEvent event) {
        if (event instanceof BattleMatchChangedEvent changed) {
            MatchState state = changed.current().matchState();
            if ((state == MatchState.ENDING && configuration.despawnOnMatchEnding())
                || (state == MatchState.RESETTING && configuration.despawnOnResetting())
                || ((state == MatchState.FAILED || state == MatchState.STOPPED || state == MatchState.STOPPING)
                    && configuration.despawnOnFailed())) {
                despawnAll("match-" + state);
            }
        } else if (event instanceof BattleRuntimeClosedEvent) {
            despawnAll("battle-runtime-closed");
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        VehicleRuntimeId runtimeId = anchors.get(event.getRightClicked().getUniqueId());
        if (runtimeId == null) return;
        event.setCancelled(true);
        ManagedVehicle vehicle = vehicles.get(runtimeId);
        if (vehicle == null) return;
        UUID playerUuid = event.getPlayer().getUniqueId();
        if (vehicle.driverUuid == null) {
            vehicle.driverUuid = playerUuid;
            event.getPlayer().sendMessage("§aYou claimed driver control for " + vehicle.definition.displayName());
        } else if (vehicle.driverUuid.equals(playerUuid)) {
            vehicle.driverUuid = null;
            event.getPlayer().sendMessage("§eYou released driver control.");
        } else {
            event.getPlayer().sendMessage("§cDriver seat is occupied.");
        }
        vehicle.lastUpdatedAt = Instant.now();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerUuid = event.getPlayer().getUniqueId();
        vehicles.values().forEach(vehicle -> {
            if (playerUuid.equals(vehicle.driverUuid)) {
                vehicle.driverUuid = null;
                vehicle.lastUpdatedAt = Instant.now();
            }
        });
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (anchors.containsKey(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        VehicleRuntimeId runtimeId = anchors.remove(event.getEntity().getUniqueId());
        if (runtimeId != null) {
            vehicles.remove(runtimeId);
            despawnCount++;
        }
    }

    private void tick() {
        if (closed) return;
        List<VehicleRuntimeId> removed = vehicles.entrySet().stream()
            .filter(entry -> entry.getValue().anchor.entity().isDead()
                || !entry.getValue().anchor.entity().isValid())
            .map(Map.Entry::getKey)
            .toList();
        removed.forEach(runtimeId -> {
            ManagedVehicle vehicle = vehicles.remove(runtimeId);
            if (vehicle != null) anchors.remove(vehicle.anchor.entity().getUniqueId());
        });
    }

    private boolean playing() {
        BattleRuntimeSnapshot snapshot = battleRuntime.snapshot();
        return snapshot.available() && snapshot.matchState() == MatchState.PLAYING;
    }

    private boolean canUseLocation(Location location) {
        if (location == null || location.getWorld() == null) return false;
        World world = location.getWorld();
        return world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    private VehicleRuntimeId findRuntime(String value) {
        if (value == null || value.isBlank()) return null;
        List<VehicleRuntimeId> matches = vehicles.keySet().stream()
            .filter(id -> id.toString().startsWith(value) || id.shortText().equalsIgnoreCase(value))
            .toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private void despawn(VehicleRuntimeId runtimeId, String reason) {
        ManagedVehicle vehicle = vehicles.remove(runtimeId);
        if (vehicle == null) return;
        anchors.remove(vehicle.anchor.entity().getUniqueId());
        vehicle.state = VehicleRuntimeState.DESPAWNING;
        try {
            adapter.despawn(vehicle.anchor);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING,
                "[warsim-vehicle] Vehicle despawn failed: " + reason, exception);
        }
        vehicle.state = VehicleRuntimeState.DESPAWNED;
        despawnCount++;
    }

    private void despawnAll(String reason) {
        List<VehicleRuntimeId> ids = new ArrayList<>(vehicles.keySet());
        ids.forEach(id -> despawn(id, reason));
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (tickTaskId != -1) {
            Bukkit.getScheduler().cancelTask(tickTaskId);
            tickTaskId = -1;
        }
        try {
            if (battleSubscription != null) battleSubscription.close();
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "[warsim-vehicle] Battle subscription close failed.", exception);
        }
        HandlerList.unregisterAll(this);
        despawnAll("plugin-close");
        try {
            if (commandRegistration != null) commandRegistration.close();
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "[warsim-vehicle] Command unregister failed.", exception);
        }
    }

    private static String nullToNone(String value) {
        return value == null || value.isBlank() ? "none" : value;
    }

    private interface VehicleModelAdapter {
        String name();

        VehicleAnchor spawn(VehicleDefinition definition, VehicleRuntimeId runtimeId, Location location);

        void despawn(VehicleAnchor anchor);
    }

    private final class FallbackAnchorAdapter implements VehicleModelAdapter {
        @Override
        public String name() {
            return "bukkit-armor-stand-fallback";
        }

        @Override
        public VehicleAnchor spawn(VehicleDefinition definition, VehicleRuntimeId runtimeId, Location location) {
            if (!"ARMOR_STAND".equalsIgnoreCase(definition.anchorEntityType())) {
                throw new IllegalArgumentException("Only ARMOR_STAND anchor is supported in T-018");
            }
            ArmorStand stand = (ArmorStand) location.getWorld().spawnEntity(location, EntityType.ARMOR_STAND);
            stand.setGravity(false);
            stand.setInvulnerable(true);
            stand.setPersistent(false);
            stand.setCustomName("WarSim Vehicle: " + definition.displayName());
            stand.setCustomNameVisible(true);
            stand.getPersistentDataContainer().set(runtimeKey, PersistentDataType.STRING, runtimeId.toString());
            stand.getPersistentDataContainer().set(vehicleKey, PersistentDataType.STRING, definition.id().toString());
            return new VehicleAnchor(stand, name());
        }

        @Override
        public void despawn(VehicleAnchor anchor) {
            anchor.entity().remove();
        }
    }

    private record VehicleAnchor(Entity entity, String bindingStatus) {
    }

    private final class ManagedVehicle {
        private final VehicleRuntimeId runtimeId;
        private final VehicleDefinition definition;
        private final VehicleAnchor anchor;
        private UUID driverUuid;
        private double speed;
        private final Instant spawnedAt;
        private Instant lastUpdatedAt;
        private VehicleRuntimeState state = VehicleRuntimeState.SPAWNED;

        private ManagedVehicle(
            VehicleRuntimeId runtimeId,
            VehicleDefinition definition,
            VehicleAnchor anchor,
            UUID driverUuid,
            double speed,
            Instant spawnedAt,
            Instant lastUpdatedAt
        ) {
            this.runtimeId = runtimeId;
            this.definition = definition;
            this.anchor = anchor;
            this.driverUuid = driverUuid;
            this.speed = speed;
            this.spawnedAt = spawnedAt;
            this.lastUpdatedAt = lastUpdatedAt;
        }

        private VehicleRuntimeSnapshot snapshot() {
            Location location = anchor.entity().getLocation();
            return new VehicleRuntimeSnapshot(
                runtimeId,
                definition.id(),
                definition.displayName(),
                location.getWorld() == null ? "unknown" : location.getWorld().getName(),
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getYaw(),
                location.getPitch(),
                speed,
                Optional.ofNullable(driverUuid),
                state,
                anchor.bindingStatus(),
                spawnedAt,
                lastUpdatedAt
            );
        }
    }
}
