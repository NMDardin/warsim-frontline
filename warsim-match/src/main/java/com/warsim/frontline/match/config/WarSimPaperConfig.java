package com.warsim.frontline.match.config;

import com.warsim.frontline.api.classes.CombatClassConfiguration;
import com.warsim.frontline.api.match.MatchConfiguration;
import com.warsim.frontline.api.node.NodeDescriptor;
import com.warsim.frontline.api.node.NodeIds;
import com.warsim.frontline.api.node.NodeType;
import com.warsim.frontline.api.objective.ObjectiveConfiguration;
import com.warsim.frontline.api.roster.RosterConfiguration;
import com.warsim.frontline.api.ticket.TicketConfiguration;
import com.warsim.frontline.database.config.DatabaseConfiguration;
import com.warsim.frontline.destruction.DestructionPaperConfiguration;
import com.warsim.frontline.match.resourcepack.ResourcePackPaperConfiguration;
import com.warsim.frontline.match.performance.PerformanceConfiguration;
import com.warsim.frontline.network.MessageCodec;
import com.warsim.frontline.network.redis.RedisConfiguration;
import com.warsim.frontline.vehicles.VehicleConfiguration;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public record WarSimPaperConfig(
    NodeDescriptor node,
    boolean debugLogging,
    boolean pluginMessagingEnabled,
    String channel,
    long requestTimeoutMillis,
    int maximumMessageBytes,
    Set<String> allowedTargets,
    boolean modelEngineEnabled,
    boolean mythicMobsEnabled,
    DatabaseConfiguration database,
    RedisConfiguration redis,
    MatchConfiguration match,
    String matchConfigurationError,
    RoundResetPaperConfiguration roundReset,
    String roundResetConfigurationError,
    RosterConfiguration roster,
    String rosterConfigurationError,
    ObjectiveConfiguration objectives,
    String objectiveConfigurationError,
    TicketConfiguration tickets,
    String ticketConfigurationError,
    DestructionPaperConfiguration destruction,
    String destructionConfigurationError,
    VehicleConfiguration vehicles,
    String vehicleConfigurationError,
    ResourcePackPaperConfiguration resourcePack,
    String resourcePackConfigurationError,
    PerformanceConfiguration performance,
    String performanceConfigurationError,
    CombatClassConfiguration classes,
    String classConfigurationError,
    DeploymentPaperConfiguration deployment,
    String deploymentConfigurationError,
    CombatPaperConfiguration combat,
    String combatConfigurationError
) {
    private static final Pattern CHANNEL = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

    public WarSimPaperConfig {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(allowedTargets, "allowedTargets");
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(redis, "redis");
        Objects.requireNonNull(match, "match");
        Objects.requireNonNull(roundReset, "roundReset");
        Objects.requireNonNull(roster, "roster");
        Objects.requireNonNull(objectives, "objectives");
        Objects.requireNonNull(tickets, "tickets");
        Objects.requireNonNull(destruction, "destruction");
        Objects.requireNonNull(vehicles, "vehicles");
        Objects.requireNonNull(resourcePack, "resourcePack");
        Objects.requireNonNull(performance, "performance");
        Objects.requireNonNull(classes, "classes");
        Objects.requireNonNull(deployment, "deployment");
        Objects.requireNonNull(combat, "combat");
        if (!CHANNEL.matcher(channel).matches() || channel.length() > 64) {
            throw new IllegalArgumentException("Invalid plugin messaging channel");
        }
        if (requestTimeoutMillis < 1000 || requestTimeoutMillis > 30000) {
            throw new IllegalArgumentException("request-timeout-millis must be 1000-30000");
        }
        if (maximumMessageBytes < 1024 || maximumMessageBytes > 32767) {
            throw new IllegalArgumentException("maximum-message-bytes must be 1024-32767");
        }
        allowedTargets = Set.copyOf(allowedTargets);
        if (allowedTargets.stream().anyMatch(target -> !NodeIds.isValid(target))) {
            throw new IllegalArgumentException("allowed-targets contains an invalid node ID");
        }
    }

    public WarSimPaperConfig(
        NodeDescriptor node,
        boolean debugLogging,
        boolean pluginMessagingEnabled,
        String channel,
        long requestTimeoutMillis,
        int maximumMessageBytes,
        Set<String> allowedTargets,
        boolean modelEngineEnabled,
        boolean mythicMobsEnabled,
        DatabaseConfiguration database,
        RedisConfiguration redis,
        MatchConfiguration match,
        String matchConfigurationError,
        RosterConfiguration roster,
        String rosterConfigurationError
    ) {
        this(
            node, debugLogging, pluginMessagingEnabled, channel, requestTimeoutMillis,
            maximumMessageBytes, allowedTargets, modelEngineEnabled, mythicMobsEnabled,
            database, redis, match, matchConfigurationError,
            RoundResetPaperConfiguration.disabled(), "Round Reset is not configured",
            roster, rosterConfigurationError,
            ObjectiveConfiguration.disabled(), null, TicketConfiguration.disabled(), null,
            DestructionPaperConfiguration.disabled(), "Destruction is not configured",
            VehicleConfiguration.disabled(), "Vehicles are not configured",
            ResourcePackPaperConfiguration.disabled(), "ResourcePack is not configured",
            PerformanceConfiguration.disabled(Path.of("performance-reports")), null,
            CombatClassConfiguration.defaults(true), null,
            DeploymentPaperConfiguration.disabled(), null,
            CombatPaperConfiguration.disabled(), null
        );
    }

    public static WarSimPaperConfig safeDefaults() {
        return new WarSimPaperConfig(
            new NodeDescriptor("official-war-01", NodeType.OFFICIAL_BATTLE),
            false,
            true,
            MessageCodec.DEFAULT_CHANNEL,
            5000,
            MessageCodec.DEFAULT_MAXIMUM_MESSAGE_BYTES,
            Set.of("official-war-01"),
            true,
            true,
            DatabaseConfiguration.disabledDefaults(),
            RedisConfiguration.defaults(),
            MatchConfiguration.defaults(true),
            null,
            RoundResetPaperConfiguration.disabled(),
            "Round Reset safely disabled because full config loading failed",
            RosterConfiguration.defaults(true),
            null,
            ObjectiveConfiguration.disabled(),
            "Objective safely disabled because full config loading failed",
            TicketConfiguration.disabled(),
            "Ticket safely disabled because full config loading failed",
            DestructionPaperConfiguration.disabled(),
            "Destruction safely disabled because full config loading failed",
            VehicleConfiguration.disabled(),
            "Vehicles safely disabled because full config loading failed",
            ResourcePackPaperConfiguration.disabled(),
            "ResourcePack safely disabled because full config loading failed",
            PerformanceConfiguration.disabled(Path.of("performance-reports")),
            "Performance safely disabled because full config loading failed",
            CombatClassConfiguration.defaults(true),
            null,
            DeploymentPaperConfiguration.disabled(),
            "Deployment safely disabled because full config loading failed",
            CombatPaperConfiguration.disabled(),
            "Combat safely disabled because full config loading failed"
        );
    }

    public List<String> sortedAllowedTargets() {
        return allowedTargets.stream().sorted().toList();
    }
}
