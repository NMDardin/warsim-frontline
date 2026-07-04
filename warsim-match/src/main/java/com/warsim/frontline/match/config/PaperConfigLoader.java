package com.warsim.frontline.match.config;

import com.warsim.frontline.api.node.NodeDescriptor;
import com.warsim.frontline.api.node.NodeType;
import com.warsim.frontline.api.classes.*;
import com.warsim.frontline.api.match.MatchConfiguration;
import com.warsim.frontline.api.roster.RosterConfiguration;
import com.warsim.frontline.api.roster.TeamSide;
import com.warsim.frontline.api.objective.*;
import com.warsim.frontline.api.ticket.*;
import com.warsim.frontline.api.weapon.WeaponId;
import com.warsim.frontline.database.config.DatabaseConfiguration;
import com.warsim.frontline.database.config.DatabaseEnvironmentOverrides;
import com.warsim.frontline.destruction.DestructionPaperConfiguration;
import com.warsim.frontline.destruction.DestructionProtectedRegion;
import com.warsim.frontline.match.performance.PerformanceConfiguration;
import com.warsim.frontline.network.redis.RedisConfiguration;
import com.warsim.frontline.network.redis.RedisEnvironmentOverrides;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class PaperConfigLoader {
    private PaperConfigLoader() {
    }

    public static WarSimPaperConfig load(JavaPlugin plugin, Logger logger) {
        try {
            File dataFolder = plugin.getDataFolder();
            Files.createDirectories(dataFolder.toPath());
            File configFile = new File(dataFolder, "config.yml");
            if (!configFile.isFile()) {
                try (InputStream source = plugin.getResource("config.yml")) {
                    if (source == null) {
                        throw new IOException("Bundled config.yml is missing");
                    }
                    Files.copy(source, configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(configFile);
            NodeDescriptor node = new NodeDescriptor(
                yaml.getString("node.id", "official-war-01"),
                NodeType.valueOf(yaml.getString("node.type", "OFFICIAL_BATTLE"))
            );
            Set<String> targets = new HashSet<>(yaml.getStringList("transfer.allowed-targets"));
            java.util.List<Integer> warmupAnnouncements =
                yaml.getIntegerList("match.announcements.warmup-countdown-seconds");
            if (warmupAnnouncements.isEmpty()) {
                warmupAnnouncements = java.util.List.of(60, 30, 10, 5, 4, 3, 2, 1);
            }
            MatchConfiguration matchConfiguration;
            String matchConfigurationError = null;
            try {
                matchConfiguration = new MatchConfiguration(
                    yaml.getBoolean(
                        "match.enabled",
                        node.type() == NodeType.OFFICIAL_BATTLE
                    ),
                    yaml.getString("match.mode", MatchConfiguration.OFFENSIVE_MODE),
                    yaml.getInt("match.players.minimum-to-start", 40),
                    yaml.getInt("match.players.maximum", 100),
                    yaml.getBoolean("match.players.cancel-warmup-below-minimum", true),
                    yaml.getBoolean("match.players.allow-mid-round-join", true),
                    yaml.getInt("match.timing.warmup-seconds", 60),
                    yaml.getInt("match.timing.round-duration-seconds", 2700),
                    yaml.getInt("match.timing.ending-seconds", 15),
                    yaml.getInt("match.timing.reset-timeout-seconds", 30),
                    yaml.getBoolean("match.automation.auto-start", true),
                    yaml.getBoolean("match.automation.auto-cycle", true),
                    yaml.getBoolean("match.announcements.enabled", true),
                    warmupAnnouncements
                );
            } catch (RuntimeException exception) {
                matchConfiguration = MatchConfiguration.defaults(true);
                matchConfigurationError = exception.getMessage();
                logger.log(Level.SEVERE, "[warsim-match] Match配置无效，仅Match模块将进入FAILED。", exception);
            }
            RoundResetPaperConfiguration roundResetConfiguration;
            String roundResetConfigurationError = null;
            try {
                roundResetConfiguration = loadRoundResetConfiguration(
                    yaml,
                    node.type() == NodeType.OFFICIAL_BATTLE && matchConfiguration.enabled()
                );
            } catch (RuntimeException exception) {
                roundResetConfiguration = RoundResetPaperConfiguration.disabled();
                roundResetConfigurationError = exception.getMessage();
                logger.log(Level.SEVERE,
                    "[warsim-reset] Round Reset configuration is invalid; Match reset will fail closed.",
                    exception);
            }
            RosterConfiguration rosterConfiguration;
            String rosterConfigurationError = null;
            try {
                boolean teamsEnabled = yaml.getBoolean(
                    "teams.enabled", node.type() == NodeType.OFFICIAL_BATTLE
                );
                boolean squadsEnabled = yaml.getBoolean(
                    "squads.enabled", node.type() == NodeType.OFFICIAL_BATTLE
                );
                if (!"AUTO_BALANCE".equals(yaml.getString(
                    "teams.assignment.mode", "AUTO_BALANCE"
                ))) {
                    throw new IllegalArgumentException(
                        "teams.assignment.mode must be AUTO_BALANCE"
                    );
                }
                rosterConfiguration = new RosterConfiguration(
                    teamsEnabled,
                    squadsEnabled,
                    yaml.getInt("teams.assignment.maximum-difference", 1),
                    yaml.getBoolean("teams.assignment.deterministic-tie-break", true),
                    yaml.getInt("teams.attackers.maximum-players", 50),
                    yaml.getInt("teams.defenders.maximum-players", 50),
                    yaml.getString("teams.attackers.display-name", "进攻方"),
                    yaml.getString("teams.defenders.display-name", "防守方"),
                    yaml.getInt("squads.maximum-per-team", 10),
                    yaml.getInt("squads.maximum-members", 5),
                    yaml.getBoolean("squads.assignment.auto-assign", true),
                    yaml.getBoolean("squads.assignment.prefer-existing-squads", true),
                    yaml.getBoolean("squads.switching.enabled", true),
                    yaml.getBoolean("squads.switching.allow-during-waiting", true),
                    yaml.getBoolean("squads.switching.allow-during-warmup", true),
                    yaml.getBoolean("squads.switching.allow-during-playing", true),
                    yaml.getInt("squads.switching.cooldown-seconds", 15),
                    yaml.getBoolean("squads.reconnect.restore-assignment", true),
                    yaml.getInt("squads.reconnect.grace-seconds", 120)
                );
                if (teamsEnabled
                    && rosterConfiguration.attackersMaximumPlayers()
                        + rosterConfiguration.defendersMaximumPlayers()
                        < matchConfiguration.maximumPlayers()) {
                    throw new IllegalArgumentException(
                        "Team capacity must cover Match maximum players"
                    );
                }
                if (node.type() == NodeType.OFFICIAL_BATTLE && teamsEnabled
                    && (rosterConfiguration.attackersMaximumPlayers() != 50
                        || rosterConfiguration.defendersMaximumPlayers() != 50
                        || rosterConfiguration.maximumSquadsPerTeam() != 10
                        || rosterConfiguration.maximumMembersPerSquad() != 5)) {
                    throw new IllegalArgumentException(
                        "Official Battle requires 50v50 and ten five-player squads per team"
                    );
                }
            } catch (RuntimeException exception) {
                rosterConfiguration = RosterConfiguration.defaults(
                    node.type() == NodeType.OFFICIAL_BATTLE
                );
                rosterConfigurationError = exception.getMessage();
                logger.log(
                    Level.SEVERE,
                    "[warsim-squad] Roster配置无效，仅Roster与Match模块将进入FAILED。",
                    exception
                );
            }
            ObjectiveConfiguration objectiveConfiguration;
            String objectiveConfigurationError = null;
            try {
                boolean enabled = yaml.getBoolean(
                    "objectives.enabled", node.type() == NodeType.OFFICIAL_BATTLE
                );
                ArrayList<ObjectiveDefinition> definitions = new ArrayList<>();
                LinkedHashMap<ObjectiveId, ObjectiveSectorId> pointSectors = new LinkedHashMap<>();
                var points = yaml.getConfigurationSection("objectives.points");
                if (points != null) {
                    for (String key : points.getKeys(false).stream().sorted().toList()) {
                        String path = "objectives.points." + key;
                        ObjectiveId objectiveId = new ObjectiveId(key);
                        definitions.add(new ObjectiveDefinition(
                            objectiveId,
                            yaml.getString(path + ".display-name", key),
                            new ObjectiveRegion(
                                yaml.getString(path + ".world", ""),
                                yaml.getDouble(path + ".center.x"),
                                yaml.getDouble(path + ".center.y"),
                                yaml.getDouble(path + ".center.z"),
                                yaml.getDouble(path + ".radius"),
                                yaml.getDouble(path + ".vertical-range")
                            ),
                            ObjectiveOwner.valueOf(
                                yaml.getString(path + ".initial-owner", "NEUTRAL").toUpperCase()
                            ),
                            yaml.getBoolean(path + ".locked", false),
                            new ObjectiveCaptureRules(
                                yaml.getInt(path + ".capture.base-seconds", 30),
                                yaml.getInt(path + ".capture.maximum-effective-players", 4),
                                yaml.getDouble(path + ".capture.additional-player-rate", .5),
                                EmptyBehavior.valueOf(yaml.getString(
                                    path + ".capture.empty-behavior", "RETURN_TO_OWNER"
                                ).toUpperCase()),
                                ContestedBehavior.valueOf(yaml.getString(
                                    path + ".capture.contested-behavior", "FREEZE"
                                ).toUpperCase())
                            ),
                            new ObjectiveRewards(
                                yaml.getInt(path + ".rewards.attackers-capture-tickets", 0),
                                yaml.getInt(path + ".rewards.defenders-capture-tickets", 0)
                            )
                        ));
                        if (yaml.contains(path + ".sector")) {
                            pointSectors.put(objectiveId,
                                new ObjectiveSectorId(yaml.getString(path + ".sector", "")));
                        }
                    }
                }
                ObjectiveSectorConfiguration sectors = loadObjectiveSectorConfiguration(
                    yaml, enabled, definitions, pointSectors
                );
                objectiveConfiguration = new ObjectiveConfiguration(
                    enabled,
                    yaml.getInt("objectives.scan-interval-ticks", 5),
                    definitions,
                    sectors
                );
            } catch (RuntimeException exception) {
                objectiveConfiguration = ObjectiveConfiguration.disabled();
                objectiveConfigurationError = exception.getMessage();
                logger.log(
                    Level.SEVERE,
                    "[warsim-objective] Objective配置无效，仅Objective模块将进入FAILED。",
                    exception
                );
            }
            TicketConfiguration ticketConfiguration;
            String ticketConfigurationError = null;
            try {
                boolean enabled = yaml.getBoolean(
                    "tickets.enabled", node.type() == NodeType.OFFICIAL_BATTLE
                );
                ticketConfiguration = new TicketConfiguration(
                    enabled,
                    new TicketSideConfiguration(
                        yaml.getBoolean("tickets.attackers.enabled", enabled),
                        yaml.getInt("tickets.attackers.initial", enabled ? 300 : 0),
                        yaml.getInt("tickets.attackers.maximum", enabled ? 500 : 0)
                    ),
                    new TicketSideConfiguration(
                        yaml.getBoolean("tickets.defenders.enabled", false),
                        yaml.getInt("tickets.defenders.initial", 0),
                        yaml.getInt("tickets.defenders.maximum", 0)
                    ),
                    yaml.getBoolean(
                        "tickets.behavior.end-match-on-attackers-depleted", true
                    )
                );
            } catch (RuntimeException exception) {
                ticketConfiguration = TicketConfiguration.disabled();
                ticketConfigurationError = exception.getMessage();
                logger.log(
                    Level.SEVERE,
                    "[warsim-ticket] Ticket配置无效，仅Ticket模块将进入FAILED。",
                    exception
                );
            }
            DestructionPaperConfiguration destructionConfiguration;
            String destructionConfigurationError = null;
            try {
                destructionConfiguration = loadDestructionConfiguration(
                    yaml,
                    node.type() == NodeType.OFFICIAL_BATTLE && matchConfiguration.enabled()
                );
            } catch (RuntimeException exception) {
                destructionConfiguration = DestructionPaperConfiguration.disabled();
                destructionConfigurationError = exception.getMessage();
                logger.log(
                    Level.SEVERE,
                    "[warsim-destruction] Destruction configuration is invalid; controlled destruction will fail closed.",
                    exception
                );
            }
            PerformanceConfiguration performanceConfiguration;
            String performanceConfigurationError = null;
            try {
                performanceConfiguration = new PerformanceConfiguration(
                    yaml.getBoolean("performance.enabled", node.type() == NodeType.OFFICIAL_BATTLE),
                    yaml.getInt("performance.maximum-metrics", 128),
                    yaml.getInt("performance.samples-per-metric", 512),
                    yaml.getLong("performance.alerts.warning-threshold-nanos", 25_000_000L),
                    yaml.getLong("performance.alerts.critical-threshold-nanos", 50_000_000L),
                    yaml.getLong("performance.alerts.cooldown-millis", 10_000L),
                    yaml.getInt("performance.alerts.maximum-history", 64),
                    yaml.getInt("performance.reports.maximum-files", 20),
                    dataFolder.toPath().resolve(yaml.getString(
                        "performance.reports.directory", "performance-reports"
                    )).normalize(),
                    yaml.getBoolean("performance.synthetic.enabled", false),
                    yaml.getInt("performance.synthetic.executor-queue-capacity", 1),
                    yaml.getInt("performance.synthetic.default-warmup-iterations", 5),
                    yaml.getInt("performance.synthetic.default-measurement-iterations", 50),
                    yaml.getInt("performance.synthetic.maximum-iterations", 10_000),
                    yaml.getLong("performance.synthetic.maximum-duration-millis", 30_000)
                );
            } catch (RuntimeException exception) {
                performanceConfiguration = PerformanceConfiguration.disabled(
                    dataFolder.toPath().resolve("performance-reports")
                );
                performanceConfigurationError = exception.getMessage();
                logger.log(
                    Level.SEVERE,
                    "[warsim-perf] Performance配置无效，仅Performance子系统将进入DISABLED。",
                    exception
                );
            }
            CombatClassConfiguration classConfiguration;
            String classConfigurationError = null;
            try {
                classConfiguration = loadClassConfiguration(yaml);
            } catch (RuntimeException exception) {
                classConfiguration = CombatClassConfiguration.defaults(
                    yaml.getBoolean("classes.enabled", true)
                );
                classConfigurationError = exception.getMessage();
                logger.log(Level.SEVERE,
                    "[warsim-classes] Class配置无效，仅Class子系统降级。", exception);
            }
            DeploymentPaperConfiguration deploymentConfiguration;
            String deploymentConfigurationError = null;
            try {
                deploymentConfiguration = loadDeploymentConfiguration(yaml);
            } catch (RuntimeException exception) {
                deploymentConfiguration = DeploymentPaperConfiguration.disabled();
                deploymentConfigurationError = exception.getMessage();
                logger.log(Level.SEVERE,
                    "[warsim-classes] Deployment配置无效，仅Deployment子系统禁用。", exception);
            }
            CombatPaperConfiguration combatConfiguration;
            String combatConfigurationError = null;
            try {
                combatConfiguration = loadCombatConfiguration(yaml, node.type() == NodeType.OFFICIAL_BATTLE);
            } catch (RuntimeException exception) {
                combatConfiguration = CombatPaperConfiguration.disabled();
                combatConfigurationError = exception.getMessage();
                logger.log(Level.SEVERE,
                    "[warsim-combat] Combat/HUD configuration is invalid; only combat is disabled.",
                    exception);
            }
            return new WarSimPaperConfig(
                node,
                yaml.getBoolean("logging.debug", false),
                yaml.getBoolean("network.plugin-messaging.enabled", true),
                yaml.getString("network.plugin-messaging.channel", "warsim:control"),
                yaml.getLong("network.plugin-messaging.request-timeout-millis", 5000),
                yaml.getInt("network.plugin-messaging.maximum-message-bytes", 8192),
                targets,
                yaml.getBoolean("integrations.model-engine.enabled", true),
                yaml.getBoolean("integrations.mythic-mobs.enabled", true),
                DatabaseEnvironmentOverrides.apply(
                    new DatabaseConfiguration(
                        yaml.getBoolean("database.enabled", false),
                        yaml.getString(
                            "database.jdbc-url",
                            "jdbc:postgresql://127.0.0.1:5432/warsim_frontline"
                        ),
                        yaml.getString("database.username", "warsim_frontline"),
                        yaml.getString("database.password", ""),
                        yaml.getString("database.schema", "warsim_frontline"),
                        yaml.getBoolean("database.migrations-enabled", true),
                        yaml.getInt("database.pool.maximum-size", 8),
                        yaml.getInt("database.pool.minimum-idle", 2),
                        yaml.getLong("database.pool.connection-timeout-millis", 5000),
                        yaml.getLong("database.pool.validation-timeout-millis", 3000),
                        yaml.getLong("database.pool.idle-timeout-millis", 600000),
                        yaml.getLong("database.pool.max-lifetime-millis", 1800000),
                        yaml.getLong("database.pool.leak-detection-threshold-millis", 0),
                        yaml.getInt("database.executor.threads", 4),
                        yaml.getInt("database.executor.queue-capacity", 1000),
                        yaml.getLong("database.executor.shutdown-timeout-millis", 5000),
                        yaml.getInt("database.health-check-interval-seconds", 30)
                    ),
                    System.getenv()
                ),
                RedisEnvironmentOverrides.apply(
                    new RedisConfiguration(
                        yaml.getBoolean("redis.enabled", false),
                        yaml.getString("redis.uri", "redis://127.0.0.1:6379"),
                        yaml.getString("redis.username", ""),
                        yaml.getString("redis.password", ""),
                        yaml.getInt("redis.database", 0),
                        yaml.getString("redis.namespace", "warsim:frontline:v1"),
                        yaml.getBoolean("redis.tls.enabled", false),
                        yaml.getBoolean("redis.tls.verify-hostname", true),
                        yaml.getLong("redis.connection.timeout-millis", 5000),
                        yaml.getLong("redis.connection.reconnect-delay-millis", 2000),
                        yaml.getLong("redis.heartbeat.interval-millis", 5000),
                        yaml.getLong("redis.heartbeat.ttl-millis", 15000),
                        yaml.getBoolean("redis.streams.enabled", true),
                        yaml.getLong("redis.streams.block-millis", 2000),
                        yaml.getInt("redis.streams.batch-size", 20),
                        yaml.getLong("redis.streams.claim-idle-millis", 15000),
                        yaml.getLong("redis.streams.message-ttl-millis", 30000),
                        yaml.getInt("redis.streams.maximum-attempts", 3),
                        yaml.getInt("redis.streams.deduplication-ttl-seconds", 120),
                        yaml.getInt("redis.streams.maximum-payload-bytes", 16384)
                    ),
                    System.getenv()
                ),
                matchConfiguration,
                matchConfigurationError,
                roundResetConfiguration,
                roundResetConfigurationError,
                rosterConfiguration,
                rosterConfigurationError,
                objectiveConfiguration,
                objectiveConfigurationError,
                ticketConfiguration,
                ticketConfigurationError,
                destructionConfiguration,
                destructionConfigurationError,
                performanceConfiguration,
                performanceConfigurationError,
                classConfiguration,
                classConfigurationError,
                deploymentConfiguration,
                deploymentConfigurationError,
                combatConfiguration,
                combatConfigurationError
            );
        } catch (IOException | RuntimeException exception) {
            logger.log(
                Level.SEVERE,
                "[warsim-match] 配置加载或验证失败，已使用安全默认值：" + exception.getMessage(),
                exception
            );
            return WarSimPaperConfig.safeDefaults();
        }
    }

    private static RoundResetPaperConfiguration loadRoundResetConfiguration(
        YamlConfiguration yaml,
        boolean officialMatchEnabled
    ) {
        boolean enabled = yaml.getBoolean("match.reset.enabled", officialMatchEnabled);
        ResetHoldingSpawn holdingSpawn = null;
        if (yaml.contains("match.reset.holding-spawn.world")) {
            holdingSpawn = new ResetHoldingSpawn(
                yaml.getString("match.reset.holding-spawn.world", ""),
                yaml.getDouble("match.reset.holding-spawn.x"),
                yaml.getDouble("match.reset.holding-spawn.y"),
                yaml.getDouble("match.reset.holding-spawn.z"),
                (float) yaml.getDouble("match.reset.holding-spawn.yaw"),
                (float) yaml.getDouble("match.reset.holding-spawn.pitch")
            );
        }
        Set<String> transientWorlds = yaml.getStringList("match.reset.transient-worlds")
            .stream()
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        RoundResetPaperConfiguration configuration = new RoundResetPaperConfiguration(
            enabled,
            yaml.getInt("match.reset.start-delay-ticks", 1),
            yaml.getBoolean("match.reset.evacuate-online-players", enabled),
            holdingSpawn,
            transientWorlds,
            yaml.getInt("match.reset.maximum-transient-entities", 10000)
        );
        if (officialMatchEnabled && !configuration.enabled()) {
            throw new IllegalArgumentException("Official Battle requires match.reset.enabled=true");
        }
        return configuration;
    }

    private static CombatPaperConfiguration loadCombatConfiguration(
        YamlConfiguration yaml,
        boolean officialBattle
    ) {
        boolean combatEnabled = yaml.getBoolean("combat.enabled", officialBattle);
        boolean hudEnabled = yaml.getBoolean("hud.enabled", officialBattle);
        boolean killFeedEnabled = yaml.getBoolean("kill-feed.enabled", officialBattle);
        boolean feedbackEnabled = yaml.getBoolean("feedback.enabled", officialBattle);
        return new CombatPaperConfiguration(
            combatEnabled,
            hudEnabled,
            killFeedEnabled,
            feedbackEnabled,
            yaml.getInt("combat.assists.maximum-contributors-per-target", 8),
            yaml.getDouble("combat.assists.minimum-damage", 20.0),
            yaml.getDouble("combat.assists.minimum-percentage", 0.20),
            CombatPaperConfiguration.seconds(yaml.getLong("combat.assists.attribution-ttl-seconds", 10)),
            Math.max(1, yaml.getLong("combat.damage-correlation-ttl-millis", 2000)) * 1_000_000L,
            yaml.getBoolean("combat.environmental-attribution.enabled", false),
            CombatPaperConfiguration.seconds(yaml.getLong("combat.environmental-attribution.ttl-seconds", 3)),
            yaml.getBoolean("combat.friendly-fire.enabled", false),
            yaml.getBoolean("combat.spawn-protection.enabled", officialBattle),
            CombatPaperConfiguration.seconds(yaml.getLong("combat.spawn-protection.duration-seconds", 5)),
            yaml.getBoolean("combat.spawn-protection.block-incoming-damage", true),
            yaml.getBoolean("combat.spawn-protection.remove-on-weapon-fire", true),
            yaml.getBoolean("combat.spawn-protection.remove-on-melee-attack", true),
            yaml.getBoolean("combat.spawn-protection.remove-on-objective-presence", true),
            yaml.getBoolean("combat.spawn-protection.remove-on-leave-radius", true),
            yaml.getDouble("combat.spawn-protection.protected-radius", 5.0),
            yaml.getInt("hud.update-interval-ticks", 10),
            yaml.getInt("kill-feed.maximum-entries", 20),
            CombatPaperConfiguration.seconds(yaml.getLong("kill-feed.ttl-seconds", 8)),
            Math.max(0, yaml.getLong("kill-feed.throttle-millis", 1000)) * 1_000_000L
        );
    }

    private static DestructionPaperConfiguration loadDestructionConfiguration(
        YamlConfiguration yaml,
        boolean officialMatchEnabled
    ) {
        boolean enabled = yaml.getBoolean("destruction.enabled", officialMatchEnabled);
        boolean restoreEnabled = yaml.getBoolean("destruction.restore.enabled", enabled);
        Set<String> worlds = yaml.getStringList("destruction.worlds").stream()
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<Material> allowList = materials(yaml.getStringList("destruction.materials.allow-list"),
            "destruction.materials.allow-list");
        Set<Material> denyList = materials(yaml.getStringList("destruction.materials.deny-list"),
            "destruction.materials.deny-list");
        ArrayList<DestructionProtectedRegion> protectedRegions = new ArrayList<>();
        var regions = yaml.getConfigurationSection("destruction.protected-regions");
        if (regions != null) {
            for (String id : regions.getKeys(false).stream().sorted().toList()) {
                String path = "destruction.protected-regions." + id;
                protectedRegions.add(new DestructionProtectedRegion(
                    id,
                    yaml.getString(path + ".world", ""),
                    yaml.getDouble(path + ".min.x"),
                    yaml.getDouble(path + ".min.y"),
                    yaml.getDouble(path + ".min.z"),
                    yaml.getDouble(path + ".max.x"),
                    yaml.getDouble(path + ".max.y"),
                    yaml.getDouble(path + ".max.z")
                ));
            }
        }
        DestructionPaperConfiguration configuration = new DestructionPaperConfiguration(
            enabled,
            yaml.getBoolean("destruction.record.entity-explosions", true),
            yaml.getBoolean("destruction.record.block-explosions", true),
            yaml.getBoolean("destruction.record.player-block-breaks", false),
            yaml.getBoolean("destruction.record.player-block-places", false),
            restoreEnabled,
            yaml.getInt("destruction.restore.maximum-blocks-per-match", 50_000),
            yaml.getInt("destruction.restore.maximum-blocks-per-reset", 50_000),
            worlds,
            allowList,
            denyList,
            protectedRegions
        );
        if (officialMatchEnabled && !configuration.enabled()) {
            throw new IllegalArgumentException("Official Battle requires destruction.enabled=true");
        }
        return configuration;
    }

    private static ObjectiveSectorConfiguration loadObjectiveSectorConfiguration(
        YamlConfiguration yaml,
        boolean objectivesEnabled,
        ArrayList<ObjectiveDefinition> objectives,
        Map<ObjectiveId, ObjectiveSectorId> pointSectors
    ) {
        boolean enabled = yaml.getBoolean("objectives.sectors.enabled", false);
        if (!enabled) return ObjectiveSectorConfiguration.disabled();
        if (!objectivesEnabled) {
            throw new IllegalArgumentException("objectives.sectors requires objectives.enabled=true");
        }
        var section = yaml.getConfigurationSection("objectives.sectors.definitions");
        if (section == null) {
            throw new IllegalArgumentException("objectives.sectors.definitions is required");
        }
        ArrayList<ObjectiveSectorDefinition> definitions = new ArrayList<>();
        LinkedHashMap<ObjectiveId, ObjectiveSectorId> sectorOwners = new LinkedHashMap<>();
        for (String key : section.getKeys(false).stream().sorted().toList()) {
            String path = "objectives.sectors.definitions." + key;
            ObjectiveSectorId sectorId = new ObjectiveSectorId(key);
            ArrayList<ObjectiveId> objectiveIds = new ArrayList<>();
            for (String objective : yaml.getStringList(path + ".objective-ids")) {
                ObjectiveId objectiveId = new ObjectiveId(objective);
                objectiveIds.add(objectiveId);
                ObjectiveSectorId previous = sectorOwners.put(objectiveId, sectorId);
                if (previous != null && !previous.equals(sectorId)) {
                    throw new IllegalArgumentException(
                        "objective belongs to multiple sectors: " + objectiveId
                    );
                }
            }
            definitions.add(new ObjectiveSectorDefinition(
                sectorId,
                yaml.getString(path + ".display-name", key),
                yaml.getInt(path + ".order"),
                objectiveIds
            ));
        }
        for (Map.Entry<ObjectiveId, ObjectiveSectorId> entry : pointSectors.entrySet()) {
            ObjectiveSectorId owner = sectorOwners.get(entry.getKey());
            if (!entry.getValue().equals(owner)) {
                throw new IllegalArgumentException(
                    "objective sector mismatch: " + entry.getKey()
                );
            }
        }
        return new ObjectiveSectorConfiguration(
            true,
            new ObjectiveSectorId(yaml.getString("objectives.sectors.initial-sector", "")),
            SectorCompletionMode.valueOf(yaml.getString(
                "objectives.sectors.completion-mode", "ALL_OBJECTIVES_CAPTURED"
            ).toUpperCase(java.util.Locale.ROOT)),
            yaml.getInt("objectives.sectors.advance-delay-seconds", 5),
            yaml.getBoolean("objectives.sectors.attacker-victory-on-final-sector", true),
            definitions
        );
    }

    private static Set<Material> materials(java.util.List<String> names, String path) {
        LinkedHashSet<Material> materials = new LinkedHashSet<>();
        for (String name : names) {
            Material material = Material.matchMaterial(name == null ? "" : name);
            if (material == null) {
                throw new IllegalArgumentException(path + " contains unknown material: " + name);
            }
            if (!material.isBlock()) {
                throw new IllegalArgumentException(path + " contains non-block material: " + name);
            }
            materials.add(material);
        }
        return materials;
    }

    private static CombatClassConfiguration loadClassConfiguration(YamlConfiguration yaml) {
        boolean enabled = yaml.getBoolean("classes.enabled", true);
        long revision = yaml.getLong("classes.configuration-revision", 1L);
        var section = yaml.getConfigurationSection("classes.definitions");
        if (section == null) {
            return CombatClassConfiguration.defaults(enabled);
        }
        ArrayList<CombatClassDefinition> definitions = new ArrayList<>();
        for (String key : section.getKeys(false).stream().sorted().toList()) {
            String path = "classes.definitions." + key;
            CombatClassId id = new CombatClassId(key);
            Map<EquipmentSlotType, EquipmentReference> slots = new LinkedHashMap<>();
            putEquipment(yaml, slots, EquipmentSlotType.PRIMARY_WEAPON, path + ".equipment.primary-weapon");
            putEquipment(yaml, slots, EquipmentSlotType.SECONDARY_WEAPON, path + ".equipment.secondary-weapon");
            putEquipment(yaml, slots, EquipmentSlotType.MELEE, path + ".equipment.melee");
            putEquipment(yaml, slots, EquipmentSlotType.EQUIPMENT, path + ".equipment.equipment");
            putEquipment(yaml, slots, EquipmentSlotType.THROWABLE, path + ".equipment.throwable");
            definitions.add(new CombatClassDefinition(
                id,
                yaml.getString(path + ".display-name", key),
                yaml.getInt(path + ".maximum-players", 100),
                new ClassEquipmentDefinition(slots)
            ));
        }
        return new CombatClassConfiguration(enabled, revision, definitions);
    }

    private static void putEquipment(
        YamlConfiguration yaml,
        Map<EquipmentSlotType, EquipmentReference> slots,
        EquipmentSlotType slot,
        String path
    ) {
        String type = yaml.getString(path + ".type", "empty").toLowerCase(java.util.Locale.ROOT);
        switch (type) {
            case "weapon" -> slots.put(slot,
                new WeaponEquipmentReference(new WeaponId(yaml.getString(path + ".weapon-id", ""))));
            case "craftengine" -> slots.put(slot,
                new CraftEngineEquipmentReference(yaml.getString(path + ".item-id", "")));
            case "empty" -> slots.put(slot, EmptyEquipmentReference.INSTANCE);
            default -> throw new IllegalArgumentException("Unknown equipment type at " + path);
        }
    }

    private static DeploymentPaperConfiguration loadDeploymentConfiguration(YamlConfiguration yaml) {
        boolean enabled = yaml.getBoolean("deployment.enabled", false);
        String waiting = yaml.getString("deployment.waiting-game-mode", "SPECTATOR").toUpperCase();
        String combat = yaml.getString("deployment.combat-game-mode", "SURVIVAL").toUpperCase();
        DeploymentTicketCosts costs = new DeploymentTicketCosts(
            yaml.getInt("deployment.ticket-cost.initial-deployment.attackers", 0),
            yaml.getInt("deployment.ticket-cost.initial-deployment.defenders", 0),
            yaml.getInt("deployment.ticket-cost.respawn.attackers", 1),
            yaml.getInt("deployment.ticket-cost.respawn.defenders", 0)
        );
        DeploymentPaperConfiguration.SpawnPoint waitingSpawn = spawn(yaml,
            "deployment.waiting-spawn", "waiting-spawn");
        Map<TeamSide, DeploymentPaperConfiguration.SpawnPoint> spawns = new LinkedHashMap<>();
        if (yaml.contains("deployment.spawns.attackers.world")) {
            spawns.put(TeamSide.ATTACKERS, spawn(yaml,
                "deployment.spawns.attackers", "attackers"));
        }
        if (yaml.contains("deployment.spawns.defenders.world")) {
            spawns.put(TeamSide.DEFENDERS, spawn(yaml,
                "deployment.spawns.defenders", "defenders"));
        }
        return new DeploymentPaperConfiguration(
            enabled,
            waiting,
            combat,
            yaml.getInt("deployment.countdown-seconds", 5),
            yaml.getInt("deployment.safe-radius", 2),
            yaml.getInt("deployment.maximum-spawn-candidates", 128),
            costs,
            waitingSpawn,
            spawns
        );
    }

    private static DeploymentPaperConfiguration.SpawnPoint spawn(
        YamlConfiguration yaml, String path, String id
    ) {
        String world = yaml.getString(path + ".world", "");
        if (world == null || world.isBlank()) return null;
        return new DeploymentPaperConfiguration.SpawnPoint(
            id,
            world,
            yaml.getDouble(path + ".x"),
            yaml.getDouble(path + ".y"),
            yaml.getDouble(path + ".z"),
            (float) yaml.getDouble(path + ".yaw"),
            (float) yaml.getDouble(path + ".pitch"),
            yaml.getString(path + ".note", "模板坐标，部署到真实战斗地图前必须确认")
        );
    }
}
