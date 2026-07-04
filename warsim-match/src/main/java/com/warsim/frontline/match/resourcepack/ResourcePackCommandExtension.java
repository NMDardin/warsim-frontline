package com.warsim.frontline.match.resourcepack;

import com.warsim.frontline.admin.WarSimCommandExtension;
import com.warsim.frontline.resourcepack.ResourcePackManifestEntry;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.bukkit.command.CommandSender;

final class ResourcePackCommandExtension implements WarSimCommandExtension {
    private final PaperResourcePackCoordinator coordinator;

    ResourcePackCommandExtension(PaperResourcePackCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @Override
    public String name() {
        return "resourcepack";
    }

    @Override
    public boolean execute(CommandSender sender, String[] arguments) {
        if (!sender.hasPermission("warsim.admin.resourcepack")) {
            sender.sendMessage("§c你没有权限查看资源包诊断。");
            return true;
        }
        if (arguments.length != 1) {
            usage(sender);
            return true;
        }
        switch (arguments[0].toLowerCase(Locale.ROOT)) {
            case "status" -> status(sender);
            case "inspect" -> inspect(sender);
            case "players" -> players(sender);
            default -> usage(sender);
        }
        return true;
    }

    private void status(CommandSender sender) {
        sender.sendMessage("§6WarSim ResourcePack");
        sender.sendMessage("§fEnabled：§a" + coordinator.configuration().enabled());
        sender.sendMessage("§fState：§a" + coordinator.status());
        sender.sendMessage("§fContent version：§a" + coordinator.configuration().contentVersion());
        sender.sendMessage("§fManifest version：§a" + coordinator.validation().contentVersion());
        sender.sendMessage("§fNamespace：§a" + coordinator.configuration().validation().expectedNamespace());
        sender.sendMessage("§fPack URL：§a"
            + (coordinator.configuration().pack().urlConfigured() ? "configured" : "missing"));
        sender.sendMessage("§fSHA1：§a"
            + (coordinator.configuration().pack().sha1Configured() ? "configured" : "missing"));
        sender.sendMessage("§fRequired：§a" + coordinator.configuration().pack().required());
        sender.sendMessage("§fSend on join：§a" + coordinator.configuration().send().onJoin());
        sender.sendMessage("§fValidation valid：§a" + coordinator.validation().valid());
        sender.sendMessage("§fRequired/optional entries：§a"
            + coordinator.validation().requiredEntries() + "/" + coordinator.validation().optionalEntries());
        if (coordinator.configurationError() != null) {
            sender.sendMessage("§cConfig error: " + coordinator.configurationError());
        }
        coordinator.validation().errors().stream().limit(6)
            .forEach(error -> sender.sendMessage("§cError: " + error));
        coordinator.validation().warnings().stream().limit(6)
            .forEach(warning -> sender.sendMessage("§eWarning: " + warning));
    }

    private void inspect(CommandSender sender) {
        if (coordinator.manifest() == null) {
            sender.sendMessage("§cManifest is unavailable.");
            return;
        }
        sender.sendMessage("§6ResourcePack Manifest Entries");
        for (ResourcePackManifestEntry entry : coordinator.manifest().entries()) {
            sender.sendMessage("§f" + entry.itemId()
                + " §7model=" + entry.modelId()
                + " required=" + entry.required()
                + " source=" + entry.source());
        }
    }

    private void players(CommandSender sender) {
        sender.sendMessage("§6Recent ResourcePack Players");
        List<ResourcePackPlayerRecord> records = coordinator.recentPlayers(20);
        if (records.isEmpty()) {
            sender.sendMessage("§eNo player resource-pack status has been recorded.");
            return;
        }
        for (ResourcePackPlayerRecord record : records) {
            sender.sendMessage("§f" + record.playerName()
                + " §7" + record.playerUuid().toString().substring(0, 8)
                + " status=" + record.status()
                + " version=" + record.contentVersion()
                + " at=" + DateTimeFormatter.ISO_INSTANT.format(record.updatedAt()));
        }
    }

    private static void usage(CommandSender sender) {
        sender.sendMessage("§e用法：/warsim resourcepack <status|inspect|players>");
    }

    @Override
    public List<String> complete(CommandSender sender, String[] arguments) {
        if (!sender.hasPermission("warsim.admin.resourcepack")) return List.of();
        if (arguments.length == 1) {
            String prefix = arguments[0].toLowerCase(Locale.ROOT);
            return List.of("status", "inspect", "players").stream()
                .filter(value -> value.startsWith(prefix)).toList();
        }
        return List.of();
    }
}
