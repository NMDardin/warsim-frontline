package com.warsim.frontline.match.resourcepack;

import com.warsim.frontline.admin.WarSimCommandRegistry;
import com.warsim.frontline.resourcepack.ResourcePackManifest;
import com.warsim.frontline.resourcepack.ResourcePackManifestEntry;
import com.warsim.frontline.resourcepack.ResourcePackManifestValidationResult;
import com.warsim.frontline.resourcepack.ResourcePackManifestValidator;
import com.warsim.frontline.resourcepack.ResourcePackService;
import com.warsim.frontline.resourcepack.ResourcePackStatus;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class PaperResourcePackCoordinator implements Listener, ResourcePackService, AutoCloseable {
    private static final int MAX_PLAYER_RECORDS = 100;
    private static final String MANIFEST_RESOURCE = "warsim-resourcepack-manifest.yml";

    private final JavaPlugin plugin;
    private final ResourcePackPaperConfiguration configuration;
    private final String configurationError;
    private final ResourcePackManifest manifest;
    private final ResourcePackManifestValidationResult validation;
    private final LinkedHashMap<UUID, ResourcePackPlayerRecord> players = new LinkedHashMap<>();
    private final ArrayList<Integer> scheduledTasks = new ArrayList<>();
    private final UUID packId;
    private AutoCloseable commandRegistration;
    private boolean closed;

    public PaperResourcePackCoordinator(
        JavaPlugin plugin,
        ResourcePackPaperConfiguration configuration,
        String configurationError
    ) {
        this.plugin = plugin;
        this.configuration = configuration;
        this.configurationError = configurationError;
        ResourcePackManifest loadedManifest = null;
        ResourcePackManifestValidationResult loadedValidation;
        try {
            loadedManifest = loadManifest(plugin);
            loadedValidation = configuration.validation().enabled()
                ? ResourcePackManifestValidator.validate(
                    loadedManifest,
                    configuration.validation().expectedNamespace(),
                    configuration.contentVersion(),
                    configuration.validation().requireFormalWeaponItems()
                )
                : new ResourcePackManifestValidationResult(
                    true, List.of(), List.of("Manifest validation is disabled"),
                    loadedManifest.entries().stream().filter(ResourcePackManifestEntry::required).toList().size(),
                    loadedManifest.entries().stream().filter(entry -> !entry.required()).toList().size(),
                    loadedManifest.contentVersion()
                );
        } catch (RuntimeException exception) {
            plugin.getLogger().log(
                Level.WARNING,
                "[warsim-resourcepack] Manifest loading or validation failed.",
                exception
            );
            loadedValidation = ResourcePackManifestValidationResult.failure(exception.getMessage());
        }
        this.manifest = loadedManifest;
        this.validation = loadedValidation;
        this.packId = UUID.nameUUIDFromBytes(
            (configuration.contentVersion() + "|" + configuration.pack().url())
                .getBytes(StandardCharsets.UTF_8)
        );
    }

    public void start(WarSimCommandRegistry registry) {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getServicesManager().register(
            ResourcePackService.class, this, plugin, ServicePriority.Normal
        );
        commandRegistration = registry.register(new ResourcePackCommandExtension(this));
    }

    @Override
    public String version() {
        return configuration.contentVersion();
    }

    @Override
    public ResourcePackStatus status() {
        if (configurationError != null || !configuration.enabled()) return ResourcePackStatus.DISABLED;
        if (!validation.valid() && configuration.validation().failClosedOnInvalidManifest()) {
            return ResourcePackStatus.FAILED;
        }
        if (!validation.valid() || !validation.warnings().isEmpty()
            || !configuration.pack().urlConfigured() || !configuration.pack().sha1Configured()) {
            return ResourcePackStatus.ACTIVE_WITH_WARNINGS;
        }
        return ResourcePackStatus.ACTIVE;
    }

    public ResourcePackPaperConfiguration configuration() {
        return configuration;
    }

    public String configurationError() {
        return configurationError;
    }

    public ResourcePackManifest manifest() {
        return manifest;
    }

    public ResourcePackManifestValidationResult validation() {
        return validation;
    }

    public synchronized List<ResourcePackPlayerRecord> recentPlayers(int limit) {
        ArrayList<ResourcePackPlayerRecord> records = new ArrayList<>(players.values());
        Collections.reverse(records);
        return records.stream().limit(limit).toList();
    }

    public List<String> statusLines() {
        ArrayList<String> lines = new ArrayList<>();
        lines.add("§fResourcePack 状态：§a" + status());
        lines.add("§fResourcePack 版本：§a" + configuration.contentVersion());
        lines.add("§fResourcePack URL：§a"
            + (configuration.pack().urlConfigured() ? "configured" : "missing"));
        lines.add("§fResourcePack SHA1：§a"
            + (configuration.pack().sha1Configured() ? "configured" : "missing"));
        lines.add("§fResourcePack validation：§a" + validation.valid()
            + " §frequired/optional=§a" + validation.requiredEntries()
            + "/" + validation.optionalEntries());
        if (configurationError != null) lines.add("§cResourcePack config: " + configurationError);
        validation.errors().stream().limit(3).forEach(error -> lines.add("§cResourcePack error: " + error));
        validation.warnings().stream().limit(3).forEach(warning -> lines.add("§eResourcePack warning: " + warning));
        return lines;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        record(player, ResourcePackPlayerStatus.UNKNOWN);
        if (closed || !configuration.enabled() || !configuration.send().onJoin()
            || !configuration.pack().urlConfigured() || status() == ResourcePackStatus.FAILED) {
            return;
        }
        int taskId = Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, () -> send(player),
            configuration.send().delayTicks());
        synchronized (this) {
            scheduledTasks.add(taskId);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        record(event.getPlayer(), currentStatus(event.getPlayer().getUniqueId()));
    }

    @EventHandler
    public void onResourcePackStatus(PlayerResourcePackStatusEvent event) {
        ResourcePackPlayerStatus mapped = map(event.getStatus());
        record(event.getPlayer(), mapped);
        if (configuration.send().resendOnStatusFailed()
            && List.of(
                ResourcePackPlayerStatus.FAILED_DOWNLOAD,
                ResourcePackPlayerStatus.INVALID_URL,
                ResourcePackPlayerStatus.FAILED_RELOAD
            ).contains(mapped)) {
            plugin.getLogger().warning(
                "[warsim-resourcepack] Resource pack failed for playerUuid="
                    + event.getPlayer().getUniqueId() + " status=" + mapped
                    + "; automatic resend is not implemented in T-017 base."
            );
        }
    }

    private void send(Player player) {
        if (closed || !player.isOnline() || !configuration.enabled()
            || !configuration.pack().urlConfigured() || status() == ResourcePackStatus.FAILED) {
            return;
        }
        try {
            player.setResourcePack(
                packId,
                configuration.pack().url(),
                configuration.pack().sha1(),
                Component.text(configuration.pack().prompt()),
                configuration.pack().required()
            );
            record(player, ResourcePackPlayerStatus.SENT);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(
                Level.WARNING,
                "[warsim-resourcepack] Failed to send resource pack request.",
                exception
            );
        }
    }

    private synchronized void record(Player player, ResourcePackPlayerStatus status) {
        players.remove(player.getUniqueId());
        players.put(player.getUniqueId(), new ResourcePackPlayerRecord(
            player.getUniqueId(),
            player.getName(),
            status,
            Instant.now(),
            configuration.contentVersion()
        ));
        while (players.size() > MAX_PLAYER_RECORDS) {
            UUID first = players.keySet().iterator().next();
            players.remove(first);
        }
    }

    private synchronized ResourcePackPlayerStatus currentStatus(UUID playerUuid) {
        ResourcePackPlayerRecord record = players.get(playerUuid);
        return record == null ? ResourcePackPlayerStatus.UNKNOWN : record.status();
    }

    private static ResourcePackPlayerStatus map(PlayerResourcePackStatusEvent.Status status) {
        try {
            return ResourcePackPlayerStatus.valueOf(status.name().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return ResourcePackPlayerStatus.OTHER;
        }
    }

    private static ResourcePackManifest loadManifest(JavaPlugin plugin) {
        try (InputStream source = plugin.getResource(MANIFEST_RESOURCE)) {
            if (source == null) throw new IllegalStateException("Bundled resource pack manifest is missing");
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                new InputStreamReader(source, StandardCharsets.UTF_8)
            );
            ArrayList<ResourcePackManifestEntry> entries = new ArrayList<>();
            entries.addAll(entries(yaml, "manifest.entries.items"));
            entries.addAll(entries(yaml, "manifest.entries.development-items"));
            return new ResourcePackManifest(
                yaml.getString("manifest.id", ""),
                yaml.getString("manifest.content-version", ""),
                yaml.getString("manifest.minecraft-target", ""),
                yaml.getString("manifest.namespace", ""),
                yaml.getString("manifest.pack-format-note", ""),
                entries
            );
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Unable to read bundled resource pack manifest", exception);
        }
    }

    private static List<ResourcePackManifestEntry> entries(YamlConfiguration yaml, String path) {
        ArrayList<ResourcePackManifestEntry> entries = new ArrayList<>();
        for (Map<?, ?> map : yaml.getMapList(path)) {
            entries.add(new ResourcePackManifestEntry(
                string(map, "id", ""),
                string(map, "model", ""),
                string(map, "source", ""),
                Boolean.parseBoolean(string(map, "required", "false"))
            ));
        }
        return entries;
    }

    private static String string(Map<?, ?> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (commandRegistration != null) {
            try {
                commandRegistration.close();
            } catch (Exception exception) {
                plugin.getLogger().log(Level.WARNING,
                    "[warsim-resourcepack] Command unregistration failed.", exception);
            }
        }
        for (int taskId : scheduledTasks) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
        scheduledTasks.clear();
        plugin.getServer().getServicesManager().unregister(ResourcePackService.class, this);
    }
}
