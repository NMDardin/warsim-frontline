package com.warsim.frontline.match.vehicle;

import com.warsim.frontline.admin.WarSimCommandExtension;
import com.warsim.frontline.vehicles.VehicleCombatSnapshot;
import com.warsim.frontline.vehicles.VehicleDamageResult;
import com.warsim.frontline.vehicles.VehicleDamageType;
import com.warsim.frontline.vehicles.VehicleId;
import com.warsim.frontline.vehicles.VehicleRuntimeSnapshot;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

final class VehicleCommandExtension implements WarSimCommandExtension {
    private final PaperVehicleCoordinator coordinator;

    VehicleCommandExtension(PaperVehicleCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @Override
    public String name() {
        return "vehicle";
    }

    @Override
    public boolean execute(CommandSender sender, String[] arguments) {
        if (!sender.hasPermission("warsim.admin.vehicle")) {
            sender.sendMessage("§cYou do not have permission to use vehicle diagnostics.");
            return true;
        }
        if (arguments.length == 0) {
            usage(sender);
            return true;
        }
        try {
            switch (arguments[0].toLowerCase(Locale.ROOT)) {
                case "status" -> coordinator.statusLines().forEach(sender::sendMessage);
                case "combat" -> combat(sender);
                case "definitions" -> definitions(sender);
                case "list" -> list(sender);
                case "spawn" -> spawn(sender, arguments);
                case "inspect" -> inspect(sender, arguments);
                case "despawn" -> despawn(sender, arguments);
                case "move" -> move(sender, arguments);
                case "damage" -> damage(sender, arguments);
                case "repair" -> repair(sender, arguments);
                case "destroy" -> destroy(sender, arguments);
                default -> usage(sender);
            }
        } catch (IllegalArgumentException exception) {
            sender.sendMessage("§c" + exception.getMessage());
        }
        return true;
    }

    private void combat(CommandSender sender) {
        VehicleCombatSnapshot snapshot = coordinator.combatSnapshot();
        sender.sendMessage("§6Vehicle Combat");
        sender.sendMessage("§fEnabled: §a" + snapshot.combatEnabled());
        sender.sendMessage("§fAdmin damage: §a" + snapshot.allowAdminDamage());
        sender.sendMessage("§fCancel vanilla anchor damage: §a" + snapshot.cancelVanillaAnchorDamage());
        sender.sendMessage("§fActive/destroyed/scheduled: §a" + snapshot.activeVehicles()
            + "/" + snapshot.destroyedVehicles() + "/" + snapshot.scheduledDespawns());
        sender.sendMessage("§fLast damage: §e" + snapshot.lastDamageSummary());
    }

    private void definitions(CommandSender sender) {
        sender.sendMessage("§6Vehicle Definitions");
        coordinator.definitions().forEach(definition -> sender.sendMessage("§f" + definition.id()
            + " §7name=" + definition.displayName()
            + " model=" + definition.modelEngineModelId()
            + " seats=" + definition.seats().size()
            + " maxSpeed=" + definition.movement().maximumSpeedBlocksPerSecond()
            + " health=" + fmt(definition.health().maxHealth())));
    }

    private void list(CommandSender sender) {
        sender.sendMessage("§6Active Vehicles");
        List<VehicleRuntimeSnapshot> snapshots = coordinator.snapshots();
        if (snapshots.isEmpty()) {
            sender.sendMessage("§eNo active vehicles.");
            return;
        }
        snapshots.forEach(snapshot -> sender.sendMessage("§f" + snapshot.runtimeId().shortText()
            + " §7type=" + snapshot.vehicleId()
            + " state=" + snapshot.state()
            + " hp=" + fmt(snapshot.health().currentHealth()) + "/" + fmt(snapshot.health().maxHealth())
            + " world=" + snapshot.worldName()
            + " driver=" + snapshot.driverUuid().map(uuid -> uuid.toString().substring(0, 8)).orElse("none")));
    }

    private void spawn(CommandSender sender, String[] arguments) {
        if (arguments.length != 2 && arguments.length != 6 && arguments.length != 7) {
            sender.sendMessage("§eUsage: /warsim vehicle spawn <vehicleId> [world x y z [yaw]]");
            return;
        }
        Location location;
        if (arguments.length == 2) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cConsole spawn requires world coordinates.");
                return;
            }
            location = player.getLocation();
        } else {
            World world = Bukkit.getWorld(arguments[2]);
            if (world == null) {
                sender.sendMessage("§cWorld is not loaded.");
                return;
            }
            location = new Location(
                world,
                Double.parseDouble(arguments[3]),
                Double.parseDouble(arguments[4]),
                Double.parseDouble(arguments[5]),
                arguments.length == 7 ? Float.parseFloat(arguments[6]) : 0F,
                0F
            );
        }
        coordinator.spawn(sender, new VehicleId(arguments[1].toLowerCase(Locale.ROOT)), location);
    }

    private void inspect(CommandSender sender, String[] arguments) {
        if (arguments.length != 2) {
            sender.sendMessage("§eUsage: /warsim vehicle inspect <runtimeId>");
            return;
        }
        VehicleRuntimeSnapshot snapshot = coordinator.inspect(arguments[1]);
        if (snapshot == null) {
            sender.sendMessage("§cUnknown or ambiguous vehicle runtime id.");
            return;
        }
        sender.sendMessage("§6Vehicle " + snapshot.runtimeId().shortText());
        sender.sendMessage("§fType: §a" + snapshot.vehicleId() + " §7" + snapshot.displayName());
        sender.sendMessage("§fLocation: §a" + snapshot.worldName() + " "
            + fmt(snapshot.x()) + " " + fmt(snapshot.y()) + " " + fmt(snapshot.z()));
        sender.sendMessage("§fState: §a" + snapshot.state() + " §7binding=" + snapshot.modelBindingStatus());
        sender.sendMessage("§fHealth: §a" + fmt(snapshot.health().currentHealth())
            + "/" + fmt(snapshot.health().maxHealth())
            + " destroyed=" + snapshot.health().destroyed()
            + " scheduledDespawn=" + snapshot.health().scheduledDespawn());
        sender.sendMessage("§fDriver: §a" + snapshot.driverUuid().map(Object::toString).orElse("none"));
        sender.sendMessage("§fLast damage: §e"
            + snapshot.health().lastDamageType().map(Enum::name).orElse("none")
            + " amount=" + fmt(snapshot.health().lastDamageAmount())
            + " attacker=" + snapshot.health().lastAttackerUuid().map(Object::toString).orElse("none")
            + " at=" + snapshot.health().lastDamageAt()
                .map(DateTimeFormatter.ISO_INSTANT::format).orElse("none"));
    }

    private void despawn(CommandSender sender, String[] arguments) {
        if (arguments.length != 2) {
            sender.sendMessage("§eUsage: /warsim vehicle despawn <runtimeId|all>");
            return;
        }
        coordinator.despawn(sender, arguments[1]);
    }

    private void move(CommandSender sender, String[] arguments) {
        if (arguments.length != 4) {
            sender.sendMessage("§eUsage: /warsim vehicle move <runtimeId> <forward|turn> <amount>");
            return;
        }
        coordinator.move(sender, arguments[1], arguments[2], Double.parseDouble(arguments[3]));
    }

    private void damage(CommandSender sender, String[] arguments) {
        if (arguments.length < 3 || arguments.length > 4) {
            sender.sendMessage("§eUsage: /warsim vehicle damage <runtimeId> <amount> [type]");
            return;
        }
        VehicleDamageType type = arguments.length == 4
            ? VehicleDamageType.valueOf(arguments[3].toUpperCase(Locale.ROOT))
            : VehicleDamageType.ADMIN;
        VehicleDamageResult result = coordinator.damage(
            arguments[1], Double.parseDouble(arguments[2]), type, Optional.empty(), "admin command"
        );
        if (result == null) {
            sender.sendMessage("§cUnknown or ambiguous vehicle runtime id.");
            return;
        }
        sender.sendMessage("§fVehicle damage: §a" + result.outcome()
            + " previous=" + fmt(result.previousHealth())
            + " new=" + fmt(result.newHealth())
            + " applied=" + fmt(result.damageApplied())
            + " destroyed=" + result.destroyed());
    }

    private void repair(CommandSender sender, String[] arguments) {
        if (arguments.length != 3) {
            sender.sendMessage("§eUsage: /warsim vehicle repair <runtimeId> <amount|full>");
            return;
        }
        Optional<Double> amount = "full".equalsIgnoreCase(arguments[2])
            ? Optional.empty() : Optional.of(Double.parseDouble(arguments[2]));
        if (amount.isPresent() && (!Double.isFinite(amount.get()) || amount.get() <= 0.0)) {
            sender.sendMessage("§cRepair amount must be greater than zero.");
            return;
        }
        if (!coordinator.repair(arguments[1], amount)) {
            sender.sendMessage("§cUnknown or ambiguous vehicle runtime id.");
            return;
        }
        sender.sendMessage("§aVehicle repaired.");
    }

    private void destroy(CommandSender sender, String[] arguments) {
        if (arguments.length != 2) {
            sender.sendMessage("§eUsage: /warsim vehicle destroy <runtimeId>");
            return;
        }
        VehicleDamageResult result = coordinator.destroy(arguments[1]);
        if (result == null) {
            sender.sendMessage("§cUnknown or ambiguous vehicle runtime id.");
            return;
        }
        sender.sendMessage("§fVehicle destroy: §a" + result.outcome()
            + " previous=" + fmt(result.previousHealth())
            + " destroyed=" + result.destroyed());
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static void usage(CommandSender sender) {
        sender.sendMessage("§eUsage: /warsim vehicle <status|combat|definitions|spawn|list|inspect|despawn|move|damage|repair|destroy>");
    }

    @Override
    public List<String> complete(CommandSender sender, String[] arguments) {
        if (!sender.hasPermission("warsim.admin.vehicle")) return List.of();
        if (arguments.length == 1) {
            String prefix = arguments[0].toLowerCase(Locale.ROOT);
            return List.of("status", "combat", "definitions", "spawn", "list", "inspect",
                    "despawn", "move", "damage", "repair", "destroy")
                .stream().filter(value -> value.startsWith(prefix)).toList();
        }
        if (arguments.length == 2 && "spawn".equalsIgnoreCase(arguments[0])) {
            String prefix = arguments[1].toLowerCase(Locale.ROOT);
            return coordinator.definitions().stream()
                .map(definition -> definition.id().toString())
                .filter(value -> value.startsWith(prefix))
                .toList();
        }
        if (arguments.length == 3 && "move".equalsIgnoreCase(arguments[0])) {
            String prefix = arguments[2].toLowerCase(Locale.ROOT);
            return List.of("forward", "turn").stream()
                .filter(value -> value.startsWith(prefix)).toList();
        }
        if (arguments.length == 4 && "damage".equalsIgnoreCase(arguments[0])) {
            String prefix = arguments[3].toUpperCase(Locale.ROOT);
            return List.of("ADMIN", "SMALL_ARMS", "IMPACT", "ENVIRONMENT", "SCRIPTED", "UNKNOWN")
                .stream().filter(value -> value.startsWith(prefix)).toList();
        }
        return List.of();
    }
}
